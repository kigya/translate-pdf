// Test-only, source-accurate instrumentation. Never compile this tool into the app.
import Foundation
import SwiftSyntax
import SwiftParser
import SwiftOperators
import SwiftIfConfig

struct Site: Codable { let id: Int; let kind: String; let startUTF8: Int; let endUTF8: Int; let outcomes: [String] }
struct Unsupported: Codable { let kind: String; let startUTF8: Int; let endUTF8: Int; let reason: String; let contextDeclarations: [String] }
struct Edit { let start: Int; let end: Int; let id: Int; let prefix: String; let suffix: String }
struct Configuration: Codable { let os: String; let arch: String; let environment: String?; let defines: [String]; let languageVersion: [Int]; let compilerVersion: [Int] }
struct Replacement { let start: Int; let end: Int; let text: String }
struct Region: Codable { let startUTF8: Int; let endUTF8: Int; let state: String }

final class Inventory: SyntaxAnyVisitor {
  var sites: [Site] = []
  var unsupported: [Unsupported] = []
  var reviewRequired: [Unsupported] = []
  var structural: [Unsupported] = []
  var edits: [Edit] = []
  var replacements: [Replacement] = []
  var convertedCatches: Set<Int> = []
  var handledAvailability: Set<Int> = []
  var replacedSwitchCases: Set<Int> = []
  var syntaxCounts: [String:Int] = [:]
  var regions: ConfiguredRegions?
  var configurationRegions: [Region] = []
  let namespace: String
  var usedIdentifiers: Set<String>
  init(namespace: String, usedIdentifiers: Set<String>) { self.namespace = namespace; self.usedIdentifiers = usedIdentifiers; super.init(viewMode:.sourceAccurate) }
  func freshIdentifier(_ base: String) -> String {
    var candidate = base
    var suffix = 0
    while usedIdentifiers.contains(candidate) { suffix += 1; candidate = base + "_" + String(suffix) }
    usedIdentifiers.insert(candidate)
    return candidate
  }
  func statement(_ node: some SyntaxProtocol, at position: Int, kind: String, outcome: String = "selected") {
    let id = sites.count
    sites.append(Site(id:id,kind:kind,startUTF8:node.positionAfterSkippingLeadingTrivia.utf8Offset,endUTF8:node.endPositionBeforeTrailingTrivia.utf8Offset,outcomes:[outcome]))
    edits.append(Edit(start:position,end:position,id:id,prefix:"__FBCov.selected(\"\(namespace):\(id)\", \"\(outcome)\"); ",suffix:""))
  }
  func contexts(_ node: some SyntaxProtocol) -> [String] {
    var result: [String] = []
    var ancestor: Syntax? = Syntax(node)
    while let current = ancestor {
      if let function = current.as(FunctionDeclSyntax.self) { result.append("func " + function.name.text) }
      if let type = current.as(ClassDeclSyntax.self) { result.append("class " + type.name.text) }
      if let type = current.as(StructDeclSyntax.self) { result.append("struct " + type.name.text) }
      if let type = current.as(EnumDeclSyntax.self) { result.append("enum " + type.name.text) }
      if current.is(InitializerDeclSyntax.self) { result.append("init") }
      if current.is(DeinitializerDeclSyntax.self) { result.append("deinit") }
      if current.is(ClosureExprSyntax.self) { result.append("closure") }
      ancestor = current.parent
    }
    return result.reversed()
  }
  func reject(_ node: some SyntaxProtocol, _ reason: String) {
    unsupported.append(Unsupported(kind: String(describing: node.kind), startUTF8: node.positionAfterSkippingLeadingTrivia.utf8Offset, endUTF8: node.endPositionBeforeTrailingTrivia.utf8Offset, reason: reason,contextDeclarations:contexts(node)))
  }
  func requireReview(_ node: some SyntaxProtocol, _ reason: String) {
    reviewRequired.append(Unsupported(kind:String(describing:node.kind),startUTF8:node.positionAfterSkippingLeadingTrivia.utf8Offset,endUTF8:node.endPositionBeforeTrailingTrivia.utf8Offset,reason:reason,contextDeclarations:contexts(node)))
  }
  func isStructuralTrueLoop(_ expr: ExprSyntax) -> Bool {
    guard let literal = expr.as(BooleanLiteralExprSyntax.self), literal.literal.text == "true",
          let element = expr.parent?.as(ConditionElementSyntax.self),
          let list = element.parent?.as(ConditionElementListSyntax.self), list.count == 1,
          list.parent?.is(WhileStmtSyntax.self) == true else { return false }
    return true
  }
  func wrap(_ expr: ExprSyntax, kind: String, helper: String = "boolean", outcomes: [String] = ["false", "true"]) {
    if kind == "condition", isStructuralTrueLoop(expr) {
      structural.append(Unsupported(kind:"condition",startUTF8:expr.positionAfterSkippingLeadingTrivia.utf8Offset,endUTF8:expr.endPositionBeforeTrailingTrivia.utf8Offset,reason:"Literal while true is a structural unconditional loop. No false owned source continuation exists; break/return/throw decisions remain independently measured.",contextDeclarations:contexts(expr)))
      return
    }
    let start = expr.positionAfterSkippingLeadingTrivia.utf8Offset
    let end = expr.endPositionBeforeTrailingTrivia.utf8Offset
    let id = sites.count
    sites.append(Site(id: id, kind: kind, startUTF8: start, endUTF8: end, outcomes: outcomes))
    edits.append(Edit(start:start,end:end,id:id,prefix:"__FBCov.\(helper)(\"\(namespace):\(id)\", ",suffix:")"))
  }
  func catchCondition(_ handler: CatchClauseSyntax, errorName: String) -> (String,String,String)? {
    guard handler.catchItems.count == 1, let item = handler.catchItems.first, item.whereClause == nil, let pattern = item.pattern else { return nil }
    let text = pattern.trimmedDescription
    if let match = text.range(of:"^(let|var) [A-Za-z_][A-Za-z_0-9]* as [A-Za-z_][A-Za-z_0-9]*$",options:.regularExpression), match == text.startIndex..<text.endIndex {
      let parts = text.split(separator:" ").map(String.init)
      return ("optional", "if \(parts[0]) \(parts[1]) = ", "\(errorName) as? \(parts[3])")
    }
    if let match = text.range(of:"^is [A-Za-z_][A-Za-z_0-9]*$",options:.regularExpression), match == text.startIndex..<text.endIndex { return ("boolean","if ","\(errorName) \(text)") }
    return nil
  }
  func lowerTypedCatches(_ doStatement: DoStmtSyntax) {
    let handlers = Array(doStatement.catchClauses)
    guard handlers.contains(where: {!$0.catchItems.isEmpty}) else { return }
    guard handlers.last?.catchItems.isEmpty == true, handlers.dropLast().allSatisfy({catchCondition($0,errorName:"validationOnly") != nil}) else { return }
    let caughtError = freshIdentifier("__fbCaught_" + namespace)
    for (index,handler) in handlers.enumerated() {
      let start = handler.catchKeyword.positionAfterSkippingLeadingTrivia.utf8Offset
      convertedCatches.insert(start)
      let text: String
      if let (helper, binding, expression) = catchCondition(handler,errorName:caughtError) {
        let id = sites.count
        sites.append(Site(id:id,kind:"catch-pattern",startUTF8:handler.positionAfterSkippingLeadingTrivia.utf8Offset,endUTF8:handler.body.leftBrace.position.utf8Offset,outcomes:helper == "optional" ? ["nil","some"] : ["false","true"]))
        text = (index == 0 ? "catch let \(caughtError) { " : "else ") + binding + "__FBCov.\(helper)(\"\(namespace):\(id)\", \(expression)) {"
      } else { text = "else { let error = \(caughtError); do { "; statement(handler,at:handler.body.leftBrace.endPosition.utf8Offset,kind:"catch-all",outcome:"caught") }
      replacements.append(Replacement(start:start,end:handler.body.leftBrace.endPosition.utf8Offset,text:text))
    }
    if let last = handlers.last {
      let position = last.body.rightBrace.endPosition.utf8Offset
      edits.append(Edit(start:position,end:position,id:sites.count,prefix:" } }",suffix:""))
    }
  }
  override func visitAny(_ node: Syntax) -> SyntaxVisitorContinueKind {
    var enclosing = node.parent
    while let ancestor = enclosing {
      if let clause = ancestor.as(IfConfigClauseSyntax.self), let condition = clause.condition,
         condition.position.utf8Offset <= node.position.utf8Offset,
         condition.endPosition.utf8Offset >= node.endPosition.utf8Offset { return .skipChildren }
      enclosing = ancestor.parent
    }
    if let regions, regions.isActive(node) != .active { return .skipChildren }
    if node.is(SwitchCaseSyntax.self), replacedSwitchCases.contains(node.positionAfterSkippingLeadingTrivia.utf8Offset) { return .skipChildren }
    syntaxCounts[String(describing: node.kind), default: 0] += 1
    if let statement = node.as(DoStmtSyntax.self) { lowerTypedCatches(statement) }
    if let conditional = node.as(IfExprSyntax.self), (conditional.parent?.is(CodeBlockItemSyntax.self) == true || conditional.parent?.is(ExpressionStmtSyntax.self) == true) {
      for condition in conditional.conditions {
        if case .availability(let availability) = condition.condition,
           conditional.conditions.first?.id == condition.id {
          let id = sites.count
          let token = freshIdentifier("__fbAvailable_" + namespace)
          sites.append(Site(id:id,kind:"availability",startUTF8:availability.positionAfterSkippingLeadingTrivia.utf8Offset,endUTF8:availability.endPositionBeforeTrailingTrivia.utf8Offset,outcomes:["false","true"]))
          handledAvailability.insert(condition.positionAfterSkippingLeadingTrivia.utf8Offset)
          edits.append(Edit(start:conditional.positionAfterSkippingLeadingTrivia.utf8Offset,end:conditional.endPositionBeforeTrailingTrivia.utf8Offset,id:id,prefix:"do { let \(token) = __FBCov.beginAvailable(\"\(namespace):\(id)\"); defer { __FBCov.finishAvailable(\(token)) }; ",suffix:" }"))
          let position = availability.endPositionBeforeTrailingTrivia.utf8Offset
          edits.append(Edit(start:position,end:position,id:id,prefix:", __FBCov.passAvailable(\(token))",suffix:""))
        }
      }
    }
    if let attempt = node.as(TryExprSyntax.self) {
      if attempt.questionOrExclamationMark?.text == "!" { requireReview(node,"Intrinsic forced-try assertion: pending source-specific invariant/compiler classification, not an invented alternate source arm") }
      else if attempt.expression.tokens(viewMode:.sourceAccurate).contains(where: { ["await", "&", "consume", "borrow"].contains($0.text) }) { reject(node,"Throwing expression contains unsupported concurrency/ownership/inout effects") }
      else {
        let id = sites.count
        sites.append(Site(id:id,kind:"throwing-expression",startUTF8:node.positionAfterSkippingLeadingTrivia.utf8Offset,endUTF8:node.endPositionBeforeTrailingTrivia.utf8Offset,outcomes:["success","error"]))
        edits.append(Edit(start:attempt.expression.positionAfterSkippingLeadingTrivia.utf8Offset,end:attempt.expression.endPositionBeforeTrailingTrivia.utf8Offset,id:id,prefix:"__FBCov.attempt(\"\(namespace):\(id)\", try ",suffix:")"))
      }
    }
    if let dispatch = node.as(SwitchExprSyntax.self) {
      for entry in dispatch.cases {
        guard let branch = entry.as(SwitchCaseSyntax.self) else { reject(entry,"Conditional switch cases need active dispatch lowering"); continue }
        var supported = true
        if case .case(let label) = branch.label, label.caseItems.count > 1,
           label.caseItems.allSatisfy({$0.whereClause == nil && $0.pattern.is(ExpressionPatternSyntax.self)}),
           branch.attribute == nil, branch.statements.count == 1,
           let expression = branch.statements.first?.item.as(InfixOperatorExprSyntax.self), expression.operator.trimmedDescription == "=",
           expression.leftOperand.is(DeclReferenceExprSyntax.self),
           let literal = expression.rightOperand.as(StringLiteralExprSyntax.self), literal.segments.allSatisfy({$0.is(StringSegmentSyntax.self)}) {
          var result = ""
          for item in label.caseItems {
            let id = sites.count
            sites.append(Site(id:id,kind:"switch-pattern-dispatch",startUTF8:item.pattern.positionAfterSkippingLeadingTrivia.utf8Offset,endUTF8:item.pattern.endPositionBeforeTrailingTrivia.utf8Offset,outcomes:["selected"]))
            result += "case \(item.pattern.trimmedDescription): __FBCov.selected(\"\(namespace):\(id)\", \"selected\"); \(expression.trimmedDescription)\n"
          }
          let start = branch.positionAfterSkippingLeadingTrivia.utf8Offset
          replacedSwitchCases.insert(start)
          replacements.append(Replacement(start:start,end:branch.endPositionBeforeTrailingTrivia.utf8Offset,text:result))
          continue
        }
        if case .case(let label) = branch.label {
          if label.caseItems.count != 1 || label.caseItems.contains(where: { $0.whereClause != nil || !$0.pattern.is(ExpressionPatternSyntax.self) }) { supported = false }
        }
        if branch.statements.count == 1, let expression = branch.statements.first?.item.as(ExprSyntax.self) {
          let assignment = expression.as(InfixOperatorExprSyntax.self)?.operator.trimmedDescription
          if assignment != "=" { supported = false }
        }
        if branch.tokens(viewMode:.sourceAccurate).contains(where: {$0.text == "fallthrough"}) { supported = false }
        if supported, let first = branch.statements.first { statement(branch,at:first.positionAfterSkippingLeadingTrivia.utf8Offset,kind:"switch-dispatch") }
        else { reject(branch,"Multiple/typed/where patterns, implicit expression results or fallthrough require dedicated dispatch lowering") }
      }
    }
    if let handler = node.as(CatchClauseSyntax.self), !convertedCatches.contains(handler.catchKeyword.positionAfterSkippingLeadingTrivia.utf8Offset) {
      if handler.catchItems.isEmpty { statement(node,at:handler.body.leftBrace.endPosition.utf8Offset,kind:"catch-all",outcome:"caught") }
      else { reject(node,"Typed catch match/failure requires typed dispatch lowering") }
    }
    if let condition = node.as(ConditionElementSyntax.self) {
      switch condition.condition {
      case .expression(let expr): wrap(expr, kind: "condition")
      case .optionalBinding(let binding):
        if let value = binding.initializer?.value { wrap(value, kind: "optional-binding", helper: "optional", outcomes: ["nil", "some"]) }
        else if let name = binding.pattern.as(IdentifierPatternSyntax.self)?.identifier.text {
          let id = sites.count
          sites.append(Site(id:id,kind:"implicit-optional-binding",startUTF8:binding.positionAfterSkippingLeadingTrivia.utf8Offset,endUTF8:binding.endPositionBeforeTrailingTrivia.utf8Offset,outcomes:["nil","some"]))
          let position = binding.pattern.endPositionBeforeTrailingTrivia.utf8Offset
          edits.append(Edit(start:position,end:position,id:id,prefix:" = __FBCov.optional(\"\(namespace):\(id)\", \(name))",suffix:""))
        } else { reject(node, "Non-identifier implicit binding needs dedicated lowering") }
      case .availability:
        if !handledAvailability.contains(condition.positionAfterSkippingLeadingTrivia.utf8Offset) { reject(node,"Availability outside a standalone if statement needs dedicated lowering") }
      default: reject(node, "Pattern condition requires dedicated lowering")
      }
    }
    if let chain = node.as(OptionalChainingExprSyntax.self) {
      var parent = node.parent
      var unsafe = false
      while let ancestor = parent {
        if ancestor.is(InOutExprSyntax.self) { unsafe = true }
        if let assignment = ancestor.as(InfixOperatorExprSyntax.self) {
          let op = assignment.operator.trimmedDescription
          let writes: Set<String> = ["=","+=","-=","*=","/=","%=","&=","|=","^=","<<=",">>=","&+=","&-=","&*="]
          if writes.contains(op), assignment.leftOperand.position.utf8Offset <= node.position.utf8Offset, assignment.leftOperand.endPosition.utf8Offset >= node.endPosition.utf8Offset { unsafe = true }
        }
        parent = ancestor.parent
      }
      if unsafe { reject(node,"Optional-chain lvalue/inout cannot be changed into a temporary read") }
      else { wrap(chain.expression,kind:"optional-chain-base",helper:"optional",outcomes:["nil","some"]) }
    }
    if let cast = node.as(AsExprSyntax.self) {
      if cast.questionOrExclamationMark?.text == "?" { wrap(ExprSyntax(cast),kind:"conditional-cast",helper:"optional",outcomes:["nil","some"]) }
      else if cast.questionOrExclamationMark?.text == "!" { requireReview(node,"Intrinsic forced-cast assertion: pending source-specific invariant/compiler classification; real input-reachable failure requires a production fix") }
    }
    if let check = node.as(IsExprSyntax.self) { wrap(ExprSyntax(check),kind:"type-check") }
    if node.is(ForceUnwrapExprSyntax.self) { requireReview(node,"Intrinsic forced-unwrap assertion: pending source-specific invariant/compiler classification; surrounding explicit optional decisions remain owned") }
    if let ternary = node.as(TernaryExprSyntax.self) { wrap(ternary.condition, kind: "ternary-condition") }
    if let infix = node.as(InfixOperatorExprSyntax.self) {
      let op = infix.operator.trimmedDescription
      if op == "&&" || op == "||" {
        wrap(infix.leftOperand, kind: "logical-left")
        wrap(infix.rightOperand, kind: "logical-right")
      } else if op == "??" { wrap(infix.leftOperand, kind: "coalescing-left", helper: "optional", outcomes: ["nil", "some"]) }
    }
    if let loop = node.as(ForStmtSyntax.self) {
      if loop.awaitKeyword != nil || loop.caseKeyword != nil { reject(node, "Async/pattern iteration needs ownership/effect-aware lowering") }
      else {
        wrap(loop.sequence, kind: "iterator-next", helper: "sequence", outcomes: ["exhausted", "item"])
        if let clause = loop.whereClause { wrap(clause.condition, kind: "for-where") }
      }
    }
    let blockers: [String:String] = [
                                    "repeatStmt": "Repeat-loop condition/body exits require dedicated lowering",
      "ifConfigDecl": "Active target/compiler/canImport configuration not resolved; candidate sites are not a runtime denominator",
      "matchingPatternCondition": "Pattern success/failure requires dedicated lowering",
      "awaitExpr": "Async/isolation effects require typed validation"
    ]
    if let reason = blockers[String(describing:node.kind)], !(node.is(IfConfigDeclSyntax.self) && regions != nil) { reject(node, reason) }
    if let declaration = node.as(FunctionDeclSyntax.self), ["&&", "||", "??"].contains(declaration.name.text) { reject(node, "Custom operator overload invalidates built-in operator contract") }
    if let token = node.as(TokenSyntax.self) {
      if token.text == "weak" { requireReview(node,"Weak declaration is ownership context, not a separate source branch; associated guard/optional-chain outcomes remain measured and ARC equivalence needs review") }
      else if ["unowned", "inout", "consume", "borrow", "isolated"].contains(token.text) { reject(node,"Ownership/isolation-sensitive token requires typed equivalence proof") }
    }
    return .visitChildren
  }
}
struct Output: Codable { let schemaVersion = 1; let sites: [Site]; let unsupported: [Unsupported]; let reviewRequired: [Unsupported]; let structural: [Unsupported]; let syntaxCounts: [String:Int]; let configurationRegions: [Region]; let semanticStatus = "requires-typed-validation" }
do {
  guard CommandLine.arguments.count == 6 else { throw NSError(domain:"SwiftBranch",code:1,userInfo:[NSLocalizedDescriptionKey:"usage: instrument INPUT OUTPUT AST_MANIFEST NAMESPACE"]) }
  let input = CommandLine.arguments[1], output = CommandLine.arguments[2], manifest = CommandLine.arguments[3], namespace = CommandLine.arguments[4]
  guard namespace.range(of:"^[a-zA-Z_][a-zA-Z_0-9]*$",options:.regularExpression) != nil else { throw NSError(domain:"SwiftBranch",code:2,userInfo:[NSLocalizedDescriptionKey:"invalid namespace"]) }
  let source = try String(contentsOfFile:input,encoding:.utf8)
  let parsed = Parser.parse(source:source)
  guard !parsed.hasError else { throw NSError(domain:"SwiftBranch",code:3,userInfo:[NSLocalizedDescriptionKey:"source parse error"]) }
  let folded = try OperatorTable.standardOperators.foldAll(parsed)
  let identifiers = Set(folded.tokens(viewMode:.sourceAccurate).map { $0.text.trimmingCharacters(in:CharacterSet(charactersIn:"`")) })
  let inventory = Inventory(namespace:namespace,usedIdentifiers:identifiers)
  if CommandLine.arguments[5] != "-" {
    let data = try Data(contentsOf:URL(fileURLWithPath:CommandLine.arguments[5]))
    let configuration = try JSONDecoder().decode(Configuration.self,from:data)
    let config = StaticBuildConfiguration(customConditions:Set(configuration.defines),targetOSs:[configuration.os],targetArchitectures:[configuration.arch],targetEnvironments:Set(configuration.environment.map {[$0]} ?? []),targetObjectFileFormats:["MachO"],targetAtomicBitWidths:[8,16,32,64],languageVersion:VersionTuple(components:configuration.languageVersion),compilerVersion:VersionTuple(components:configuration.compilerVersion))
    let regions = folded.configuredRegions(in:config)
    guard regions.diagnostics.isEmpty else { throw NSError(domain:"SwiftBranch",code:6,userInfo:[NSLocalizedDescriptionKey:"Conditional-compilation diagnostics: \(regions.diagnostics)"]) }
    inventory.regions = regions
    inventory.configurationRegions = regions.map { Region(startUTF8:$0.ifClause.positionAfterSkippingLeadingTrivia.utf8Offset,endUTF8:$0.ifClause.endPositionBeforeTrailingTrivia.utf8Offset,state:String(describing:$0.state)) }
  }
  inventory.walk(folded)
  let bytes = Array(source.utf8)
  var result = ""
  let starts = Dictionary(grouping:inventory.edits,by:{$0.start})
  let ends = Dictionary(grouping:inventory.edits,by:{$0.end})
  var data = Data()
  let replacementStarts = Dictionary(uniqueKeysWithValues:inventory.replacements.map { ($0.start,$0) })
  var skipUntil = -1
  for position in 0...bytes.count {
    if position < skipUntil { continue }
    if let replacement = replacementStarts[position] { data.append(Data(replacement.text.utf8)); skipUntil = replacement.end; continue }
    for edit in (ends[position] ?? []).sorted(by: { $0.start == $1.start ? $0.id > $1.id : $0.start > $1.start }) { data.append(Data(edit.suffix.utf8)) }
    for edit in (starts[position] ?? []).sorted(by: { $0.end == $1.end ? $0.id < $1.id : $0.end > $1.end }) { data.append(Data(edit.prefix.utf8)) }
    if position < bytes.count { data.append(bytes[position]) }
  }
  guard let decoded = String(data:data,encoding:.utf8) else { throw NSError(domain:"SwiftBranch",code:4) }
  result = decoded
  guard !Parser.parse(source:result).hasError else { throw NSError(domain:"SwiftBranch",code:5,userInfo:[NSLocalizedDescriptionKey:"transformed parse error"]) }
  try result.write(toFile:output,atomically:true,encoding:.utf8)
  let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted,.sortedKeys]
  try encoder.encode(Output(sites:inventory.sites,unsupported:inventory.unsupported,reviewRequired:inventory.reviewRequired,structural:inventory.structural,syntaxCounts:inventory.syntaxCounts,configurationRegions:inventory.configurationRegions)).write(to:URL(fileURLWithPath:manifest))
} catch { fputs("\(error)\n",stderr); exit(1) }
