#!/usr/bin/env python3
"""Fails if intentionally identical infrastructure classes drift across services.

Ten class names in GUARDED cover shared security, migration, tracing and event infrastructure.
Package and import differences are allowed; behavior differences require an explicit decision.

The repository now has a root Maven parent/reactor and Java Docker builds use root contexts.
Those boundaries make a shared library possible, but extracting one still requires deliberate
dependency, packaging, versioning and release changes. This check preserves consistent behavior
while service copies remain. It is not a claim that the old independent-parent build still exists.

Classes with legitimate service-specific behavior are not forced into this list. Adding a guarded
name means every copy must stay identical; removing one means a distinct implementation is intended.
"""
import collections
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "services")

# Guarded because they are currently identical everywhere they appear. Adding a name here is a claim
# that every copy MUST stay identical; removing one is a claim that a service legitimately needs its
# own variant. Both are deliberate decisions, which is the point of an explicit list.
GUARDED = [
    "GcpOtlpTraceExporterAuthConfig.java",
    "EventEnvelope.java",
    "TenantDataSourceConfig.java",
    "LoggingDomainEventPublisher.java",
    "RuntimeDbRoleGuard.java",
    "MachineCallerFilter.java",
    "MigrationOnlyMain.java",
    "MigrationRuntimePolicy.java",
    "OutboxPublisherConfiguration.java",
    "DomainEventPublisher.java",
]


def normalised(path):
    """Body only. Package and import lines legitimately differ per service; nothing else may."""
    keep = []
    for line in open(path, encoding="utf-8", errors="replace"):
        if line.startswith(("package ", "import ")):
            continue
        keep.append(line.rstrip())
    # Trailing blank lines are noise, not drift.
    while keep and not keep[-1]:
        keep.pop()
    return "\n".join(keep)


def service_of(path):
    parts = path.replace("\\", "/").split("/services/")
    return parts[1].split("/")[0] if len(parts) > 1 else "?"


def main():
    found = collections.defaultdict(list)
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in ("target", "node_modules", ".git")]
        for name in filenames:
            if name in GUARDED:
                found[name].append(os.path.join(dirpath, name))

    failures = []
    for name in GUARDED:
        paths = found.get(name, [])
        if len(paths) < 2:
            # Not an error. A class may legitimately be consolidated or removed; the list is then stale
            # and should be pruned, but that is a cleanup prompt rather than a build failure.
            print(f"  {name:<44} only {len(paths)} copy -- nothing to compare")
            continue

        variants = collections.defaultdict(list)
        for path in paths:
            variants[normalised(path)].append(service_of(path))

        if len(variants) == 1:
            services = sorted(next(iter(variants.values())))
            print(f"  {name:<44} OK  {len(paths)} identical copies ({', '.join(s.replace('-service', '') for s in services)})")
        else:
            failures.append((name, variants))

    if failures:
        print("\nDRIFT DETECTED\n")
        for name, variants in failures:
            print(f"  {name} has split into {len(variants)} variants:")
            for body, services in sorted(variants.items(), key=lambda kv: -len(kv[1])):
                names = ", ".join(sorted(s.replace("-service", "") for s in services))
                print(f"    {len(body.splitlines()):>4} lines  <- {names}")
        print(
            "\nThese classes are duplicated across services on purpose -- there is no shared module,\n"
            "because each service is an independent Maven project whose Docker build context is its own\n"
            "directory. The duplication is tolerated; DIVERGENCE is not, because a fix applied to one\n"
            "copy silently leaves the others on the old behaviour.\n\n"
            "Either apply the change to every copy, or, if this service genuinely needs its own variant,\n"
            "remove the class from GUARDED in scripts/check-duplicate-class-drift.py and say why."
        )
        return 1

    print(f"\nall {len(GUARDED)} guarded classes are consistent across services")
    return 0


if __name__ == "__main__":
    sys.exit(main())
