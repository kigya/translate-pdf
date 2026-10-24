// Read-only lexical evidence for compiler-AST contract checking.
import Foundation
import SwiftSyntax
import SwiftParser

struct TokenEvidence: Codable {
  let startUTF8: Int
  let endUTF8: Int
  let kind: String
  let text: String
}
struct CallEvidence: Codable {
  let helper: String
  let startUTF8: Int
  let endUTF8: Int
  let argumentRanges: [[Int]]
  let registryID: String?
}
struct LiteralEvidence: Codable { let startUTF8: Int; let endUTF8: Int; let kind: String }
struct Evidence: Codable { let tokens: [TokenEvidence]; let recorderCalls: [CallEvidence]; let literalRanges: [LiteralEvidence] }
final class Calls: SyntaxVisitor {
  var calls: [CallEvidence] = []
  var literals: [LiteralEvidence] = []
  override func visit(_ node: StringLiteralExprSyntax) -> SyntaxVisitorContinueKind {
    literals.append(LiteralEvidence(startUTF8:node.positionAfterSkippingLeadingTrivia.utf8Offset,endUTF8:node.endPositionBeforeTrailingTrivia.utf8Offset,kind:"stringLiteralExpr"))
    return .visitChildren
  }
  override func visit(_ node: RegexLiteralExprSyntax) -> SyntaxVisitorContinueKind {
    literals.append(LiteralEvidence(startUTF8:node.positionAfterSkippingLeadingTrivia.utf8Offset,endUTF8:node.endPositionBeforeTrailingTrivia.utf8Offset,kind:"regexLiteralExpr"))
    return .visitChildren
  }
  init() { super.init(viewMode:.sourceAccurate) }
  override func visit(_ node: FunctionCallExprSyntax) -> SyntaxVisitorContinueKind {
    guard let member = node.calledExpression.as(MemberAccessExprSyntax.self), member.base?.trimmedDescription == "__FBCov" else { return .visitChildren }
    let arguments = Array(node.arguments)
    var registryID: String? = nil
    if let literal = arguments.first?.expression.as(StringLiteralExprSyntax.self), literal.segments.count == 1,
       let segment = literal.segments.first?.as(StringSegmentSyntax.self) { registryID = segment.content.text }
    calls.append(CallEvidence(helper:member.declName.baseName.text,startUTF8:node.positionAfterSkippingLeadingTrivia.utf8Offset,
      endUTF8:node.endPositionBeforeTrailingTrivia.utf8Offset,
      argumentRanges:arguments.map {[$0.expression.positionAfterSkippingLeadingTrivia.utf8Offset,$0.expression.endPositionBeforeTrailingTrivia.utf8Offset]},registryID:registryID))
    return .visitChildren
  }
}
do {
  guard CommandLine.arguments.count == 3 else { throw NSError(domain:"SyntaxEvidence",code:1) }
  let source = try String(contentsOfFile:CommandLine.arguments[1],encoding:.utf8)
  let tree = Parser.parse(source:source)
  guard !tree.hasError else { throw NSError(domain:"SyntaxEvidence",code:2) }
  let calls = Calls(); calls.walk(tree)
  let tokens = tree.tokens(viewMode:.sourceAccurate).map {
    TokenEvidence(startUTF8:$0.positionAfterSkippingLeadingTrivia.utf8Offset,endUTF8:$0.endPositionBeforeTrailingTrivia.utf8Offset,kind:String(describing:$0.tokenKind),text:$0.text)
  }
  let encoder = JSONEncoder();encoder.outputFormatting = [.sortedKeys]
  try encoder.encode(Evidence(tokens:tokens,recorderCalls:calls.calls,literalRanges:calls.literals)).write(to:URL(fileURLWithPath:CommandLine.arguments[2]))
} catch { fputs("\(error)\n",stderr);exit(1) }
