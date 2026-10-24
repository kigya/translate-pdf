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

`prepare` audits the exact current eight-file Swift inventory across the
`TranslatePDF` and `TranslateCore` modules. Its output contains:

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

## Compiler evidence for independent certification

`certification.py` collects real original/transformed typed AST and raw,
source-located ownership SIL for an exact prepared manifest. It reads actual
`builtin-SwiftDriver` invocations, expands and hashes response files, separates
Swift definitions from forwarded Clang definitions, and checks each owned file's
configuration. Additional generated Xcode asset and Expo provider inputs are
retained in both compilations and bound separately. Unknown inputs or compiler
flags block the analysis.

Analysis uses the pinned Xcode Swift compiler and explicit SDK from the actual
job. Output destinations, scheduling and module/index caches are rebound to the
fresh private evidence directory; the shared Xcode build caches are not modified.
Compiler invocations run serially. Analysis never builds, installs or runs an app,
starts a simulator, changes production files, or changes the native coverage gate.

```sh
python3 scripts/swift-coverage/prepare_certification.py \
  --compile-log .local-private/actual-xcodebuild.log \
  --output .local-private/certification-preparation

python3 scripts/swift-coverage/certification.py \
  --manifest .local-private/certification-preparation/measurement/manifest.json \
  --compile-log .local-private/actual-xcodebuild.log \
  --output .local-private/compiler-evidence \
  --module TranslateCore --module TranslatePDF

python3 scripts/swift-coverage/audit_certification.py \
  --evidence .local-private/compiler-evidence/evidence.json \
  --output .local-private/compiler-evidence/integrity-audit.json
```

Preparation first captures the actual original module's typed AST, then derives
the narrowly supported result hints and prepares final test copies. It verifies
all owned source hashes before and after preparation. A source or generated-input
change invalidates the affected evidence rather than being silently rebound.

After separately coordinating an existing simulator, the hosted runner can use
`--configuration-manifest` with that prepared manifest. The runner binds the
original source/compiler/target configuration again and adds its test-driver
export overlay. This option supplies typing/configuration evidence; it grants no
application approval.

Per-source configurations can be supplied to the Python `prepare` API through
`configurations_by_source`. Every owned source must be bound; a shared `DEBUG`
assumption cannot stand in for actual CocoaPods/app Swift definitions. Preparation
emits a verified UTF-8 byte map for unchanged original spans and records inserted
probe spans. Replaced catch and dispatch regions remain visible for review.

Each concrete site retains its resolved original nodes, transformed source-origin
nodes, type/declaration/effect differences, source-mapped SIL operations and an
explicit family-specific proof checklist. No arbitrary helper-call erasure or
instruction-count equality is treated as semantic equivalence. Raw compiler dumps
remain authoritative. The SIL action also records loaded Swift module inputs;
Swift 6.3.3 rejects loaded-module tracing in the AST-dump action. Complete imported
Clang/header dependency certification remains a separate obligation.

The optional `Any` result repair is compiler guided: generic instrumentation under
`try?` can otherwise change contextual inference from `Any` to `Any?`. A result
annotation is offered only for an exact original call/range that resolves to
`Any` in the bound original AST. Source and AST hashes, hint ranges and original
types are checked again during evidence collection. This fixes compilation, not
the independent requirement to prove overload, error, ownership and ARC behavior.

An integrity audit verifies artifacts, actual compiler jobs, source inventories,
zero-initialized outcome inventories and recomputes the concrete comparisons from
raw AST/SIL. Its `artifactIntegrityPassed` result is distinct from semantic
certification and application coverage. Both `semanticEquivalenceCertified` and
`applicationGateApproved` remain false. An optional independent review attachment
records provenance only; an `approved` field cannot bypass unresolved proofs.

Required remaining evidence includes concrete transformation and invariant proofs,
the required configuration union, imported dependencies, independent review, and
actual loaded-image-bound runtime outcomes. Preparation and compiler analysis do
not manufacture hits or establish a 100% denominator.

`configuration_union.py` reconciles the required actual Debug simulator, Release
device and Release ARM64 simulator packets against an unconfigured original-source
inventory. It retains inactive-only source decisions for independent platform-scope
review, and never marks them excluded from owned code. `dependency_evidence.py`
can additionally record actual compiler dependency-scan graphs and hashes of
declared source/header/module-map and existing module inputs. Planned module
outputs are kept distinct from observed input artifacts. Scanner graph collection
alone is not independent dependency-closure certification.

References: [Swift compiler phases](https://www.swift.org/documentation/swift-compiler/),
[SIL stages and ownership SSA](https://github.com/swiftlang/swift/blob/main/docs/SIL/SIL.md),
[ownership rules](https://github.com/swiftlang/swift/blob/main/docs/SIL/Ownership.md),
and [compiler options](https://github.com/swiftlang/swift/blob/main/include/swift/Option/Options.td).
The installed compiler's help/output remains the evidence for the exact supported
flags and version; no additional SwiftSyntax package or SDK is downloaded.

`prove_local_contracts.py` computes narrower compiler-backed proof obligations:

```sh
python3 scripts/swift-coverage/prove_local_contracts.py \
  --packet .local-private/compiler-evidence/evidence.json \
  --output .local-private/local-contracts
```

The finite Bool/Optional, selected-delegation and throwing-callback helper rules
compare their entire actual SIL instruction,
SSA use, ownership and basic-block transfer programs. Only SSA spelling and
non-semantic debug/profiling metadata are normalized. The rules check the exact
input returned or copied, nil/Bool tests, literal outcomes and both control-flow
edges into the exact recorder. The callback rule checks one noescape callback
invocation, unchanged successful result transfer and propagation of the same
error reference. These are helper-body facts; callback capture/ARC equivalence at
the concrete source site remains separate. Unknown lowerings have no automatic rule.

A separate source-specific rule compares the retained full typed expression tree
and contextual type with the exact transformed helper argument. SwiftSyntax token
extents, compiler-resolved helper declarations and literal registry IDs bind this
comparison to concrete source bytes. Nested transformations, ambiguous mappings,
closures/captures and inserted declaration references require separate rules.
Even a passing local rule leaves concrete SIL evaluation order, ownership/weak
lifetimes, observer behavior and independent review unresolved. It cannot approve
an application or infer any runtime outcome.

Prepared manifests now retain immutable SHA-bound original source snapshots as
well as copied-span maps. This preserves replaced catch/switch bytes across later
legitimate source revisions. Local proof collection can analyze exact historical
artifacts while reporting current-source drift; it does not attest a current app
build from an old packet. Old packets without complete original bytes remain
blocked for the affected sources. All registered sites, classifications,
unsupported syntax and zero outcome inventories are retained in the local report.

`compositional_contracts.py` checks nested Bool/Optional contexts using freshly
verified helper SIL rules, exact compiler-resolved literal calls, unchanged
contextual types and complete copied-byte coverage of each original payload.
Every contracted node retains its source-specific premises. Other helper families
and unresolved captures remain pending. `statement_contracts.py` separately
checks exact selected catch/switch arms, including the original pattern and body,
after verifying the precise first probe statement. Parser-derived complete
String/Regex literal spans resolve compound-literal AST endpoints.

`sil_context_contracts.py` compares complete original and transformed function
SIL programs after a finite Bool-call contraction. It verifies the actual helper
transfer, exact declared literal ID, exclusive SSA uses of String setup and
cleanup, private lifetime, and the call's exact source location. Cross-block
lifetimes require a fully known acyclic CFG, setup dominance and destruction
postdominance; unknown shapes remain pending. Every retained
instruction, ownership operation, function signature and CFG edge must match;
only SSA/block spelling and debug locations are normalized. Quoted literal
contents remain intact. This is a conditional retained-program fact: observer
termination/disjoint effects, literal construction/resource behavior and complete
whole-program equivalence remain explicit obligations.

Local proof packets snapshot the exact implementation as well as compiler and
source evidence. None of these rules accepts an approval supplied by a JSON file,
removes registered outcomes, grants application approval or supplies runtime hits.

`observer_callback_contracts.py` checks three complete private initialization SIL
programs. Both higher-order calls must use the exact capture-free private
callbacks, substitutions and error slots. The inner callback returns the unchanged
String paired with zero UInt64 and never writes its Never error slot. Exact
compiler-loaded Swift interface bytes bind the Collection typed-throws and
Dictionary rethrows declarations. Under these language/library contracts, this
establishes the narrow callback/error-transfer fact. It does not establish trap
freedom, allocation behavior, locking, private pointer provenance or full observer
noninterference. See the [Swift rethrows declaration rules](https://docs.swift.org/latest/documentation/the-swift-programming-language/declarations/).

`registry_sil_contracts.py` derives the entire registry-initialization SIL program
from the declared data. It checks fresh allocation/storage paths, every bounded
array index, literal byte length, initializing store, ownership finalization and
the final private Dictionary store. The pinned compiler demangler verifies the
complete logical private declaration, including compressed identifier spellings.
Unknown lowerings fail closed. Its conclusion depends on the stated Swift
allocation/storage/literal ABI contracts and normal resource behavior; recorder
mutation, synchronization and full observer noninterference remain separate.

## Original runtime gap inventory

Keep original LLVM exports separate from transformed diagnostics. Re-export with
the exact unchanged instrumented binary and merged profile, retain their hashes
and compiler/source bindings, and check for LLVM mismatch diagnostics. Profile
files without embedded binary IDs cannot provide an independent binary-ID match.

Original LCOV `DA` entries can identify zero-count source-line clusters; retain
`LF`/`LH` and original LLVM summaries separately because emitted distinct line
entries can differ from summary counts. Keep all zero-count function records,
including compiler-generated closures and accessors, and mark missing file
records unmeasured. No original line/function/region observation supplies absent
Swift true/false branch outcomes or approves the experimental semantic gate.

## Measurement identity and finite roles

The production measurement digest (identity schema 2) binds owned source bytes,
transformed bodies, site/outcome registry, metric roles, selected typed facts,
compiler and bundled syntax-library hashes, and instrumentation/runtime sources.
It excludes export-driver UUIDs, artifact locations and allocation IDs in raw
compiler dumps. A separate preparation digest binds the complete per-run files,
including both original/export driver hashes, instrumenter binary and raw typed
artifacts. Runtime exports bind the execution digest, run ID and loaded image.
Never rewrite old results to fit a new identity; regenerate preparation after a
tool change. Two current hosted OS runs may share production identity while
retaining distinct execution manifests and image artifacts.

Every site has an explicit `metricRole` and `metricRule`. Atomic control/logical
predicates are proposed source conditions. A direct nested logical expression is
composite diagnostic; calls and closures are syntax boundaries, so a logical
predicate inside `contains(where:)` does not replace the outer condition.
Optional operations, errors, iterator-next observations and selected dispatch
arms have separate operation/error/iteration/dispatch roles. For-in item/exhausted
and switch/catch arm observations remain explicit mandatory coverage obligations.
**All candidate sites/outcomes and all zero counters remain retained and checked.**
No role filters the validator's missing-outcome list. The accepted branch
denominator remains null until independent policy and source review.

Optional receiver reads require original typed-AST-bound selected-declaration
facts. Non-inout selected methods, explicit value loads, declared reference
receivers and built-in tuple reads have finite rules; unknown mutating value
methods/getters retain their original code and are unsupported. Syntax-only
preparation cannot grant this permission. `prepare_certification.py` obtains
read facts from the original typed extraction and binds/recomputes them before
instrumentation.

After independently coordinated hosted runs, combine their intact receipts:

```sh
python3 scripts/swift-coverage/aggregate_ios.py \
  --run .local-private/hosted-ios18 --run .local-private/hosted-ios26 \
  --output .local-private/hosted-combined-candidate-observations.json
```

The aggregator reconstructs the export driver and actual compile audit, verifies
all raw receipts and image copies, requires equal frozen native inputs, and
rejects duplicate execution IDs or source/driver/overlay drift. It performs no
approval and does not modify original LLVM coverage. Its application/semantic
approval fields stay false. Successful host controls are not actual iOS18/26
runtime evidence.
