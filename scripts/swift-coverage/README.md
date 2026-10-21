# Experimental Swift branch measurement

This is test tooling, not an alternate acceptance gate. Apple Swift 6.3.3 exports
LLVM lines/functions/regions but no source branch records for this project's Swift.
This tool prepares source-mapped probes for a **supported subset** and explicitly
records unsupported constructs. It never edits production files or changes the
existing native coverage threshold.

The application validator intentionally fails until typed-equivalence,
ownership and invariant obligations are independently approved. Hosted actual
compile-job and loaded-module evidence is collected separately by `run_ios.py`. A fixture with
all supported outcomes hit is not evidence that the application passed coverage.
Do not inject these counters into LLVM summaries or remove unsupported sites.

## Run

Requires the existing full Xcode at `/Applications/Xcode.app`; uses its bundled
SwiftSyntax, SwiftParser and SwiftOperators libraries, language mode 5. No downloads
or global configuration changes occur.

```sh
python3 scripts/swift-coverage/coverage.py prepare \
  --output .local-private/swift-coverage-owned
python3 -m unittest discover -s scripts/swift-coverage/tests -v
python3 scripts/swift-coverage/coverage.py validate \
  --manifest .local-private/swift-coverage-owned/manifest.json \
  --output .local-private/swift-coverage-owned/gate.json \
  .local-private/swift-coverage-owned/runtime-results.json
```

The last command fails when results are missing, drifted, incomplete, unsupported,
or not independently certified. Missing files are errors, not empty coverage.
Preparation success means only that test copies and an AST inventory were created.

`prepare` audits the exact current six-file Swift inventory. Its output contains:

- untouched-original, transformed-copy and AST-manifest SHA-256 values;
- source UTF-8 ranges, branch kinds, all expected outcomes and zero-ready registry;
- explicit unsupported nodes and unresolved semantic/configuration status;
- tool/compiler provenance, VFS overlay and generated locked runtime;
- a digest embedded in the runtime and checked against every result.

Test builds pass `-vfsoverlay overlay.json` and explicitly include the generated
`BranchRuntime.swift`. The differential tests use **original source paths** with
the overlay, so successful transformed behavior demonstrates overlay application
for those exact compile commands. Application Xcode compile-job attestation and a
hosted XCTest run are still required. Preserve original Release builds and LLVM
line/function/region measurement separately.

The recorder exports actual per-outcome hit counts, run ID, containing loaded-image
SHA-256 and Mach-O `LC_UUID`, actual Swift module, process ID and registry digest. Its private recording method uses
an `NSLock`; integer overflow or an unknown outcome invalidates results. The only
driver call required is `__FBCov.export(to:runID:)`. Tests must not invoke identity
helpers directly to manufacture production hits; integration must audit test-source
references and bind the runtime, driver and compiler-input hashes. The containing image is identified through a private module-local NSObject marker;
its UUID is read from its Mach-O load commands. The process executable path is
retained separately and never substituted for a core/debug dylib identity.


## Hosted iOS probe

After coordinating use of the existing booted simulator with the build owner:

```sh
python3 scripts/swift-coverage/run_ios.py \
  --simulator-id APPROVED_EXISTING_BOOTED_UUID \
  --output .local-private/my-fresh-swift-ios-probe --jobs 1
```

For an incremental local run, `--derived-data-path` may select an existing
project-local cache. Every owned source must still have a fresh matching compile
job. On hosts affected by SwiftBuild's sequential stdout/stderr discovery hang,
the explicit `--stream-safe-clang` flag uses `ios/scripts/clang-stream-safe.py`
for that invocation. It runs the actual Xcode compiler, captures both complete
macro-discovery streams, then closes stdout before emitting stderr. Ordinary
compilation executes the real compiler directly. This changes no global Xcode
setting; wrapper and native bridge/framework input bytes are attested before and
after the run. The default path continues to use Xcode's compiler directly.

The runner never boots/resets a simulator or edits the project. It generates an
explicit VFS test-driver copy whose only recorder calls export both actual module
registries at XCTest class teardown. `prepare` injects a module-local runtime into
one private transformed source in each module before final hashes are written.
The digest uses the transformed-body hash to avoid a self-referential runtime
digest; the manifest separately locks each final injected-copy SHA. Appending or
changing bytes after preparation fails validation.

The isolated Xcode run records actual compiler commands/response-file hashes,
source hashes, target/SDK/defines/language/overlay and both loaded module images.
Raw simulator outputs and immutable loaded-image copies are retained with UUID,
SHA, runtime OS and export time. A successful build/probe exit is **not** application
coverage acceptance: `branch-gate.json` remains an independent fail-closed result.
Original LLVM and Release validation must still run on untouched original sources.

## Supported transformations and limits

The finite source grammar currently supported is:

- Bool if/guard/while conditions, both lazy logical operands and ternary conditions;
- explicit and identifier-shorthand optional bindings, lazy coalescing left values,
  optional **read** receivers, conditional casts and type checks;
- synchronous ordinary for iteration/where, observing each real next() item or
  exhaustion without synthesizing exhaustion after break/return;
- throwing expressions using rethrows + an autoclosure, preserving shorthand `$0`
  scope and try? flattening; unsafe ownership/inout/async forms fail closed;
- single-pattern switch dispatch; multiple expression patterns only for the current
  app's narrowly recognized constant string assignment to an identifier;
- unconditional catch and finite typed catch chains (`let/var name as Type`,
  `is Type`, final unconditional catch), observing each actual match/failure;
- standalone if statements with a **first** availability condition. The original
  #available syntax/refinement stays in place; a scoped token records false if the
  availability check failed and true before later comma conditions run;
- SwiftIfConfig active regions from explicit os/arch/environment/defines/language/
  compiler configuration. Inactive regions remain in the manifest with their state.
  Unresolved imports/features or configuration diagnostics fail preparation.

```sh
python3 scripts/swift-coverage/run_host.py \
  --output .local-private/my-fresh-swift-host-probe
```

The output directory must not exist. This runs the existing production host test
corpus twice, without editing its driver, compares output, exports original LLVM
coverage separately and records the real candidate counters. Probe success and
application gate status are different fields; the application gate stays red.
Use `coverage.py prepare --configuration file.json` for an explicit configuration.
The configuration has `os`, `arch`, optional `environment`, `defines`,
`languageVersion` and `compilerVersion` arrays. It must match real compiler jobs;
preparation alone is no attestation that an Xcode target used those inputs.

Typed catch lowering uses an explicit fresh catch identifier absent from all
original AST tokens. Typed handlers keep their original outer lexical `error`;
the final catch recreates its implicit `error` in a separate enclosing scope so
an original body-local `let error` can still shadow it. Regression coverage includes
the independently reported `outermarker` reproducer and deliberate name collisions.

A sole literal `true` while condition stays unchanged as an unconditional structural
loop. It has no runtime-varying false source continuation and creates no fictitious
false obligation. Its body conditions and exits remain measured. Structural nodes
are recorded separately from `sites`; they do not add counts or hits.

`unsupported` contains unimplemented source decisions. `reviewRequired` separately
retains intrinsic assertions and ownership context; these block app acceptance
until the exact invariant/type/compiler obligations are resolved. They are not
converted into fabricated alternate source continuations. This distinction leaves
all surrounding explicit guard/cast/optional/loop decisions in the real registry.

Every prepared source includes `classificationInventory`; the same pending items
are collected in `classification-inventory.json`. Each has an ID derived from the
original source SHA, node kind and byte range, original text, line/UTF-8 column,
AST declaration context and an explicit pending review status. This inventory
includes forced unwrap/cast/try checks, weak/ownership-sensitive context, unsafe
optional-chain writes and constant Bool predicates. It never invents failure hits,
marks a generated check N/A wholesale or changes an original protected operation.
An independent review must distinguish generated runtime traps guarded by proven
API invariants from real source decisions or bugs reachable through valid input.
The literal-true structural classification preserves the complete source-bound
inventory and its controlling body branches; no fictitious false execution is
added for `while true`. Bind recommendations without approving them:

```sh
python3 scripts/swift-coverage/review_classifications.py \
  --inventory .local-private/swift-coverage-ios-catch-fix/classification-inventory.json \
  --review docs/agent-reports/ux2-swift-branch-independent-review.md \
  --output .local-private/swift-coverage-ios-catch-fix/classification-dispositions.json
```

The binder checks exact source/range identities, hashes, unique IDs and all site
counts. Every disposition stays `classified-pending-required-proofs`; no review
recommendation automatically passes coverage. Source/tool binary drift and changed
generated runtime/VFS overlay also invalidate validation.

Return/throw/break/continue/defer remain the original control transfers, with
branches measured at their controlling conditions, throwing calls and dispatch.
Unknown typed semantics, unimplemented patterns, optional mutation/inout, forced
traps pending classification, weak lifetime review and incomplete compile-job
provenance block application certification. Syntax and successful compilation
alone cannot prove built-in overload selection, contextual inference, reference
lifetimes, copyability or actor isolation. All original LLVM lines/functions/
regions and the owned-source inventory remain separately mandatory.

The regression corpus runs real original/transformed binaries and checks values,
side-effect order, lazy fallback, errors/catch/rethrow/defer, iteration, break/
continue/early return, cooperative cancellation, weak capture release behavior,
#available with a later false comma condition and concurrent counter updates.
Negative controls cover partial/missing/duplicate results, mutated source/AST/
transformed bytes, manifests, registry/outcomes/counts and binary UUID/hash.
A single host OS deliberately cannot pass both outcomes of a fixed availability
threshold. Fixtures do not certify untested grammar or application lifecycle.
