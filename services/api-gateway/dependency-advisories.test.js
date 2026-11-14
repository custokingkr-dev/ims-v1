'use strict';
// Advisories that npm audit and Trivy cannot see yet.
//
// npm audit and the Trivy release gate (build-release.yml, severity HIGH,CRITICAL, exit-code 1)
// read GitHub's global advisory database. An advisory a project publishes on its own repository
// reaches that database only after GitHub reviews it, which can take days. Until then nothing in
// CI flags a vulnerable version in this lockfile, and Dependabot alerts are disabled on this
// repository. Each entry below is such an advisory for a package the gateway ships.
//
// Remove an entry once `gh api advisories/<id>` stops returning 404: from then on npm audit and
// the Trivy gate enforce it. Bounds are inclusive and copied from the advisory's
// "vulnerable_version_range" (`gh api repos/<owner>/<repo>/security-advisories`).
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const REPOSITORY_ONLY_ADVISORIES = [
  {
    id: 'GHSA-qrww-3xmv-qhgm',
    severity: 'high',
    summary: 'host detector runs PATH-resolved ioreg and kenv (untrusted search path)',
    packageName: '@opentelemetry/resources',
    affected: [
      { min: '1.10.0', max: '2.12.0', fixedIn: '2.12.1' },
      { min: '3.0.0', max: '3.1.0', fixedIn: '3.1.1' },
    ],
  },
];

function parseVersion(version) {
  const match = /^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+.*)?$/.exec(version);
  assert.ok(match, `not a semantic version: ${version}`);
  return { core: [Number(match[1]), Number(match[2]), Number(match[3])], prerelease: match[4] || null };
}

function compareVersions(left, right) {
  const a = parseVersion(left);
  const b = parseVersion(right);
  for (let i = 0; i < 3; i += 1) {
    if (a.core[i] !== b.core[i]) return a.core[i] < b.core[i] ? -1 : 1;
  }
  if (a.prerelease === b.prerelease) return 0;
  if (a.prerelease === null) return 1;
  if (b.prerelease === null) return -1;
  return a.prerelease < b.prerelease ? -1 : 1;
}

function affectedRange(advisory, version) {
  return advisory.affected.find(
    (range) => compareVersions(version, range.min) >= 0 && compareVersions(version, range.max) <= 0,
  );
}

function lockedCopies(packageName) {
  const lock = JSON.parse(fs.readFileSync(path.join(__dirname, 'package-lock.json'), 'utf8'));
  const suffix = `node_modules/${packageName}`;
  return Object.entries(lock.packages)
    .filter(([location]) => location === suffix || location.endsWith(`/${suffix}`))
    .map(([location, entry]) => ({ location, version: entry.version }));
}

test('repository-only advisory ranges match their published affected and patched versions', () => {
  const [resources] = REPOSITORY_ONLY_ADVISORIES;
  for (const version of ['1.10.0', '2.9.0', '2.12.0', '3.0.0', '3.1.0']) {
    assert.ok(affectedRange(resources, version), `${version} should be inside ${resources.id}`);
  }
  for (const version of ['1.9.0', '2.12.1', '2.13.0', '3.1.1', '3.2.0']) {
    assert.equal(affectedRange(resources, version), undefined, `${version} should be outside ${resources.id}`);
  }
});

test('gateway lockfile holds no version named by an advisory npm audit cannot see yet', () => {
  for (const advisory of REPOSITORY_ONLY_ADVISORIES) {
    const copies = lockedCopies(advisory.packageName);
    assert.ok(
      copies.length > 0,
      `${advisory.packageName} is no longer in package-lock.json; delete the ${advisory.id} entry`,
    );
    const vulnerable = copies
      .map((copy) => ({ ...copy, range: affectedRange(advisory, copy.version) }))
      .filter((copy) => copy.range);
    assert.deepEqual(
      vulnerable.map((copy) => `${copy.location}@${copy.version} (fixed in ${copy.range.fixedIn})`),
      [],
      `${advisory.id} (${advisory.severity}): ${advisory.summary}`,
    );
  }
});
