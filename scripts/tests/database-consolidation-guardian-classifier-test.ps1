$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "../..")
$evidencePath = Join-Path $repoRoot "scripts/database-consolidation-evidence.sql"
$containerName = "ims-guardian-classifier-$([guid]::NewGuid().ToString('N').Substring(0, 10))"
$containerCreated = $false

function Invoke-Psql([string]$Sql) {
    $output = @($Sql | & docker exec -i $containerName psql -X -v ON_ERROR_STOP=1 `
        -U postgres -d ims -A -t -F '|' 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Guardian classifier PostgreSQL fixture failed: $(($output -join "`n").Trim())"
    }
    return @($output | ForEach-Object { [string]$_ })
}

try {
    $existing = @(docker ps -a --filter "name=^/$containerName$" --format '{{.Names}}')
    if ($existing.Count -gt 0) { throw "Generated test container already exists." }
    & docker run --name $containerName -e POSTGRES_PASSWORD=test -e POSTGRES_DB=ims `
        -d postgres:16-alpine *> $null
    if ($LASTEXITCODE -ne 0) { throw "Could not start PostgreSQL 16 guardian classifier fixture." }
    $containerCreated = $true

    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        & docker exec $containerName pg_isready -U postgres -d ims *> $null
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Milliseconds 500
    }
    if ($LASTEXITCODE -ne 0) { throw "PostgreSQL guardian classifier fixture did not become ready." }

    $setup = @'
CREATE SCHEMA student;
CREATE SCHEMA tenant_school;
CREATE TABLE tenant_school.schools(id BIGINT PRIMARY KEY, active boolean NOT NULL);
CREATE TABLE student.students(
    id BIGINT PRIMARY KEY, school_id BIGINT NOT NULL, father_name text,
    father_contact text, mother_name text, deleted_at timestamptz,
    version bigint NOT NULL DEFAULT 0, updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE student.guardians(
    id varchar(64) PRIMARY KEY, school_id BIGINT NOT NULL, full_name text NOT NULL,
    phone text, status text NOT NULL
);
CREATE TABLE student.student_guardians(
    id varchar(64) PRIMARY KEY, school_id BIGINT NOT NULL, student_id BIGINT NOT NULL,
    guardian_id varchar(64) NOT NULL, relationship text NOT NULL,
    is_primary boolean NOT NULL, updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE student.student_consent_events(
    id varchar(64) PRIMARY KEY, school_id bigint NOT NULL, student_id bigint NOT NULL,
    guardian_id varchar(64)
);

INSERT INTO tenant_school.schools VALUES (1,true), (2,true), (3,false);

INSERT INTO student.students VALUES
 (1,1,'Legacy A','111','Legacy Mother',NULL), (2,1,'Legacy A','111',NULL,NULL),
 (3,1,'Legacy B','222',NULL,NULL), (4,1,'Sibling X','333',NULL,NULL),
 (5,1,'Sibling Y','444',NULL,NULL), (6,1,NULL,NULL,NULL,NULL),
 (7,1,'No Link','7777777777',NULL,NULL), (8,1,'Tenant Drift','888',NULL,NULL),
 (9,1,'Cross Alpha',NULL,NULL,NULL), (10,1,NULL,NULL,'Cross Beta',NULL),
 (11,1,'Effective Solo','1111',NULL,NULL), (12,1,'Effective Other','1212',NULL,NULL),
 (13,1,'Tenant Shared','1313',NULL,NULL), (14,2,'Tenant Shared','1313',NULL,NULL),
 (15,1,'<empty>','1515',NULL,NULL), (16,1,NULL,'1515',NULL,NULL),
 (17,1,'a|b','1717',NULL,NULL), (18,1,E'line\nbreak','1818',NULL,NULL),
 (19,1,'Deleted Conflict','9999',NULL,now()),
 (20,2,NULL,NULL,NULL,NULL),
 (21,1,'Inactive Parent','2121',NULL,NULL),
 (22,1,'Other Role Parent','2222',NULL,NULL),
 (23,3,'Inactive School Parent','2323232323',NULL,NULL),
 (24,1,'Invalid Phone Parent','123','Valid Bundle Mother',NULL),
 (25,1,'Consent Parent','2525252525',NULL,NULL),
 (26,1,'V14 Parent','2626262626',NULL,NULL),
 (27,1,'Candidate Parent','2727272727',NULL,NULL),
 (28,1,'Cluster Parent','2828282828',NULL,NULL),
 (29,1,'Cluster Parent','2828282828',NULL,NULL),
 (30,1,'Bundle Father','3030303030','Bundle Mother',NULL),
 (31,1,'New Candidate Spelling','3131313131',NULL,NULL),
 (32,1,'Phone Cluster One','3232323232',NULL,NULL),
 (33,1,'Phone Cluster Two','3232323232',NULL,NULL),
 (34,1,NULL,'3434343434',NULL,NULL),
 (35,1,'Target Collision','3535353535',NULL,NULL),
 (36,4,'Missing School Parent','3636363636',NULL,NULL),
 (37,1,'Consent Drift Parent','3737373737',NULL,NULL);

INSERT INTO student.guardians VALUES
 ('g1',1,'',NULL,'ACTIVE'), ('gm1',1,'',NULL,'ACTIVE'),
 ('g2',1,'Canonical B','999','ACTIVE'), ('g3',1,'',NULL,'ACTIVE'),
 ('g6',1,'Canonical Only',NULL,'ACTIVE'), ('g8',2,'',NULL,'ACTIVE'),
 ('g-cross',1,'',NULL,'ACTIVE'), ('g-non-effective',1,'',NULL,'ACTIVE'),
 ('g-other',1,'Effective Other','1212','ACTIVE'), ('g-tenant',1,'',NULL,'ACTIVE'),
 ('g-sentinel',1,'','1515','ACTIVE'), ('g-delimiter',1,'',NULL,'ACTIVE'),
 ('g-newline',1,'',NULL,'ACTIVE'), ('g-inactive',1,'Inactive Parent','2121','INACTIVE'),
 ('g-other-role',1,'Other Role Parent','2222','ACTIVE'),
 ('g-consent',1,'Historical Parent','2525252525','INACTIVE'),
 ('g-candidate',1,'Candidate Parent','2727272727','ACTIVE'),
 ('g-phone-candidate',1,'Old Candidate Spelling','3131313131','ACTIVE');

INSERT INTO student.guardians(id, school_id, full_name, phone, status)
SELECT
 'legacy-' || md5('1:father:v14 parent:2626262626'),
 1, 'V14 Parent', '2626262626', 'ACTIVE';

INSERT INTO student.guardians(id, school_id, full_name, phone, status)
SELECT
 'guardian-repair-v1-' || md5('35:father'),
 1, 'Unrelated Existing Target', '9999999999', 'ACTIVE';

INSERT INTO student.student_consent_events VALUES
 ('c25',1,25,'g-consent'),
 ('c37',2,37,NULL);

INSERT INTO student.student_guardians VALUES
 ('l1',1,1,'g1','FATHER',true,now()), ('l2',1,2,'g1','FATHER',true,now()),
 ('lm1',1,1,'gm1','MOTHER',false,now()), ('l3',1,3,'g2','FATHER',true,now()),
 ('l4',1,4,'g3','FATHER',true,now()), ('l5',1,5,'g3','FATHER',true,now()),
 ('l6',1,6,'g6','FATHER',true,now()), ('l8',2,8,'g8','FATHER',true,now()),
 ('l9',1,9,'g-cross','FATHER',true,now()), ('l10',1,10,'g-cross','MOTHER',true,now()),
 ('l11',1,11,'g-non-effective','FATHER',true,now()),
 ('l12-primary',1,12,'g-other','FATHER',true,now()),
 ('l12-secondary',1,12,'g-non-effective','FATHER',false,now() - interval '1 day'),
 ('l13',1,13,'g-tenant','FATHER',true,now()), ('l14',2,14,'g-tenant','FATHER',true,now()),
 ('l15',1,15,'g-sentinel','FATHER',true,now()), ('l16',1,16,'g-sentinel','FATHER',true,now()),
 ('l17',1,17,'g-delimiter','FATHER',true,now()), ('l18',1,18,'g-newline','FATHER',true,now()),
 ('l19',1,19,'g1','FATHER',true,now()),
 ('l20-other-tenant',2,20,'g-delimiter','OTHER',false,now()),
 ('l21-inactive',1,21,'g-inactive','FATHER',true,now()),
 ('l22-other-role',1,22,'g-other-role','OTHER',true,now());
'@
    Invoke-Psql $setup | Out-Null

    $evidence = Get-Content -Raw -LiteralPath $evidencePath
    $start = $evidence.IndexOf("\echo 'V24-effective shared father identity conflict summary'")
    $end = $evidence.IndexOf("\echo 'reporting student projection parity'")
    if ($start -lt 0 -or $end -le $start) { throw "Guardian classifier SQL block was not found." }
    $classifier = $evidence.Substring($start, $end - $start)

    $first = Invoke-Psql "BEGIN READ ONLY;`n$classifier`nCOMMIT;"
    $second = Invoke-Psql "BEGIN READ ONLY;`n$classifier`nCOMMIT;"
    $firstText = $first -join "`n"
    $secondText = $second -join "`n"

    foreach ($expected in @(
        'SAFE_LEGACY_ONLY|FATHER|name|4|4|3',
        'SAFE_LEGACY_ONLY|FATHER|contact|4|4|3',
        'SAFE_LEGACY_ONLY|MOTHER|name|1|1|1',
        'SAFE_CREATE_UNLINKED_STUDENT|FATHER|contact|2|2|0',
        'SAFE_CREATE_UNLINKED_STUDENT|FATHER|name|2|2|0',
        'SAFE_CREATE_UNLINKED_STUDENT|MOTHER|name|1|1|0',
        'REVIEW_UNLINKED_SCHOOL_MISSING_OR_INACTIVE|FATHER|contact|2|2|0',
        'REVIEW_UNLINKED_SCHOOL_MISSING_OR_INACTIVE|FATHER|name|2|2|0',
        'REVIEW_UNLINKED_INVALID_OR_UNAPPROVED_LEGACY_VALUE|FATHER|contact|2|2|0',
        'REVIEW_UNLINKED_INVALID_OR_UNAPPROVED_LEGACY_VALUE|FATHER|name|1|1|0',
        'REVIEW_UNLINKED_INVALID_OR_UNAPPROVED_LEGACY_VALUE|MOTHER|name|1|1|0',
        'REVIEW_UNLINKED_GUARDIAN_CONSENT|FATHER|contact|2|2|0',
        'REVIEW_UNLINKED_GUARDIAN_CONSENT|FATHER|name|2|2|0',
        'REVIEW_UNLINKED_DETERMINISTIC_ID_EXISTS|FATHER|contact|2|2|0',
        'REVIEW_UNLINKED_DETERMINISTIC_ID_EXISTS|FATHER|name|2|2|0',
        'REVIEW_UNLINKED_IDENTITY_CANDIDATE|FATHER|contact|2|2|0',
        'REVIEW_UNLINKED_IDENTITY_CANDIDATE|FATHER|name|2|2|0',
        'REVIEW_UNLINKED_SHARED_LEGACY_CLUSTER|FATHER|contact|4|4|0',
        'REVIEW_UNLINKED_SHARED_LEGACY_CLUSTER|FATHER|name|4|4|0',
        'REVIEW_SHARED_DIVERGENCE|FATHER|contact|2|2|1',
        'REVIEW_SHARED_DIVERGENCE|FATHER|name|4|4|3',
        'REVIEW_SHARED_DIVERGENCE|MOTHER|name|1|1|1',
        'REVIEW_BOTH_PRESENT_DIFFERENT|FATHER|name|1|1|1',
        'REVIEW_BOTH_PRESENT_DIFFERENT|FATHER|contact|1|1|1',
        'REVIEW_TENANT_OR_LINK_ANOMALY|FATHER|name|4|4|3',
        'REVIEW_TENANT_OR_LINK_ANOMALY|FATHER|contact|4|4|3',
        'REVIEW_NORMALIZED_ONLY|FATHER|name|1|1|1',
        'REVIEW_INACTIVE_OR_MISSING_EFFECTIVE_LINK|FATHER|name|2|2|0',
        'REVIEW_INACTIVE_OR_MISSING_EFFECTIVE_LINK|FATHER|contact|2|2|0'
    )) {
        if (-not $firstText.Contains($expected)) {
            throw "Guardian classifier fixture is missing expected aggregate: $expected`n$firstText"
        }
    }

    $aggregateRows = @($first | Where-Object {
        $_ -match '^(SAFE_[A-Z_]+|REVIEW_[A-Z_]+)\|'
    })
    if ($aggregateRows.Count -ne 29) {
        throw "Guardian classifier returned unexpected aggregate rows: $($aggregateRows.Count)`n$($aggregateRows -join "`n")"
    }
    if (-not ($aggregateRows | Where-Object {
        $_ -match '\|64\|9\|5\|3\|2\|[0-9a-f]{64}\|[0-9a-f]{64}$'
    })) {
        throw "Guardian classifier returned unexpected total or safe action counts."
    }

    $fingerprintPattern = '[0-9a-f]{64}'
    $firstFingerprints = @([regex]::Matches($firstText, $fingerprintPattern) | ForEach-Object { $_.Value } | Select-Object -Unique)
    $secondFingerprints = @([regex]::Matches($secondText, $fingerprintPattern) | ForEach-Object { $_.Value } | Select-Object -Unique)
    $fingerprintDifference = @(Compare-Object $firstFingerprints $secondFingerprints)
    if ($firstFingerprints.Count -ne 2 -or $secondFingerprints.Count -ne 2 `
        -or $fingerprintDifference.Count -ne 0) {
        throw "Guardian full/safe repair plan fingerprints were missing or not repeatable."
    }

    foreach ($privateValue in @(
        'Legacy A', 'Cross Alpha', 'Cross Beta', '<empty>', 'a|b', 'line',
        'Bundle Father', 'Bundle Mother', 'Valid Bundle Mother',
        'New Candidate Spelling', 'Phone Cluster One', 'Phone Cluster Two',
        'Target Collision', 'Missing School Parent', 'Consent Drift Parent',
        'g1', 'g-cross', 'g-tenant', '111', '1717'
    )) {
        if ($firstText.Contains($privateValue)) {
            throw "Guardian classifier leaked row-level fixture data: $privateValue"
        }
    }

    Write-Host "guardian repair classifier PostgreSQL 16 fixtures passed"
} finally {
    if ($containerCreated) {
        $resolved = @(docker ps -a --filter "name=^/$containerName$" --format '{{.Names}}')
        if ($resolved.Count -eq 1 -and $resolved[0] -eq $containerName) {
            & docker rm -f $containerName *> $null
        }
    }
}
