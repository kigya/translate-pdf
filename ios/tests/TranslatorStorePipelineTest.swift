import Foundation
import SQLite3

// Store-only boundary fixture. Configured page metadata exercises SQL/JSON
// assembly, and is never presented as real PDF parsing or rendering evidence.
enum TranslateCoreBridge {
  static var pageMetadata = "{}"
  static func readPage(_ path: String, index: Int) -> String { pageMetadata }
  static func readDocxSegment(_ path:String,index:Int)->String{pageMetadata}
}

@main
enum TranslatorStorePipelineTest {
  static func main() throws {
    try testTranslationLanguagesAndDefaults()
    try testInsertedJobValidatesAgainstPinnedInputs()
    try testLegacyPipelineRejectsResumeAndRetainsPageCheckpoint()
    try testCatalogExposesThreeUnverifiedProfilesAndResources()
    try testSelectedModelSettingIsPersistedAndValidated()
    try testUnsupportedGlossaryIsRejectedBeforeJobCreation()
    try testPairScopedGlossaryAndGemmaValidation()
    try testGlossaryOptOutFreezesEmptySnapshotAndLegacyMigrationIsVersionBounded()
    try testLegacyEnglishRussianGlossaryMigration()
    try testMissingJobCannotValidate()
    try testActiveTransferProgressIsNotReportedAsDiskStorage()
    try testReadyModelsAreReconciledWithTheirFiles()
    try testStaleDownloadCallbacksCannotMutateResumedOrDeletedModels()
    try testDownloadProgressCommitCannotRaceInvalidation()
    try testOperationsWaitForBackgroundTaskRestoration()
    try testSQLiteTypesAndFailuresDoNotPartiallyCommit()
    try testDocumentJobAndExportLifecycle()
    try testLightweightProgressAndSharedDocumentDeletion()
    try testPageMetadataAndJsonFailuresRetainCheckpoints()
    try testMissingPinnedInputsAndLegacySourceMigration()
    try testDocxAndOnlineJobMigrationAndConsent()
    try testMyMemoryChunksStayUnicodeSafeAndWithinProviderLimit()
    try testMyMemoryUsesOnlyConsentScopedGetAndPausesQuotaErrors()
    try testMyMemoryPreservesParagraphWhitespaceAndDecodesEntities()
    try testMyMemoryRecognizesQuotaFlagOnSuccessStatus()
    try testMyMemoryRejectsOversizedChunkedResponsesWithoutContentLength()
    try testMyMemoryRejectsOversizedDeclaredAndLyingLengthsButAcceptsBoundedBody()
    try testMyMemoryCancellationCannotBeReplacedByLateProtocolData()
    try testDocxWarningsAndExpandedTranslationCheckpoint()
    try testSchema6LabelsAndRatingPreferencesPreserveLegacyState()
    try testLocalTranslationMemoReusesOnlyExactSuccessfulRequests()
    try testLocalTranslationMemoNeverCachesFailuresOrBlankText()
    try testLocalTranslationMemoEvictsLeastRecentlyUsedEntry()
    try testLocalTranslationMemoHonorsUtf8BudgetAndJobClear()
    try testLocalTranslationMemoUsesProductionDefaultBounds()
    print("PASS 36 TranslatorStore pipeline/catalog/language tests")
  }

  private static func testLocalTranslationMemoReusesOnlyExactSuccessfulRequests() throws {
    var calls=0
    let memo=LocalTranslationMemo()
    let original=try memo.translate("Warning",glossary:"glossary",context:"context",source:"en",target:"ru",using:{text,glossary,context,source,target in
      calls += 1
      return try! jsonString(["text":"Перевод: \(text)","tokens":17,"seconds":2.5,"request":"\(glossary)|\(context)|\(source)|\(target)"])
    })
    let repeated=try jsonObject(memo.translate("Warning",glossary:"glossary",context:"context",source:"en",target:"ru",using:{_,_,_,_,_ in
      calls += 1
      return "{}"
    }))
    let originalText=try jsonObject(original)["text"] as? String
    try expect(repeated["text"] as? String=="Перевод: Warning" && repeated["text"] as? String==originalText,"a cache hit should return the exact previously translated text")
    try expect(repeated["tokens"] as? Int==0 && repeated["seconds"] as? Int==0 && repeated["tokensPerSecond"] as? Int==0 && repeated["cacheHit"] as? Bool==true,"cache hits must not claim inference tokens or elapsed time")
    try expect(calls==1,"a repeated exact request must not invoke native inference again")
    let variations=[
      ("Warning","different glossary","context","en","ru"),
      ("Warning","glossary","different context","en","ru"),
      ("Different source","glossary","context","en","ru"),
      ("Warning","glossary","context","de","ru"),
      ("Warning","glossary","context","en","de")
    ]
    for (text,glossary,context,source,target) in variations {
      _=try memo.translate(text,glossary:glossary,context:context,source:source,target:target,using:{text,_,_,_,_ in
        calls += 1
        return try! jsonString(["text":"translated \(text)"])
      })
    }
    try expect(calls==6,"source, target, source text, glossary, and context must each be part of the exact-request cache key")
  }

  private static func testLocalTranslationMemoNeverCachesFailuresOrBlankText() throws {
    for response in ["{\"error\":\"interrupted\"}",try jsonString(["text":" \n "]),"invalid json"] {
      var calls=0
      let memo=LocalTranslationMemo()
      for _ in 0..<2 {
        do {
          _=try memo.translate("source",glossary:"",context:"",source:"en",target:"ru",using:{_,_,_,_,_ in calls += 1;return response})
        } catch {
          try expect(response=="invalid json","only malformed native JSON should surface a parse failure")
        }
      }
      try expect(calls==2,"failed or blank native responses must be retried rather than cached")
    }
  }

  private static func testLocalTranslationMemoEvictsLeastRecentlyUsedEntry() throws {
    var calls=0
    let memo=LocalTranslationMemo(maxEntries:2)
    for text in ["a","b","a","c","b"] {
      _=try memo.translate(text,glossary:"",context:"",source:"en",target:"ru",using:{text,_,_,_,_ in calls += 1;return try! jsonString(["text":text,"tokens":1,"seconds":0.1])})
    }
    try expect(calls==4,"touching a cached entry must make the untouched entry the next LRU eviction")
  }

  private static func testLocalTranslationMemoHonorsUtf8BudgetAndJobClear() throws {
    let output=try jsonString(["text":String(repeating:"Ж",count:20),"tokens":1,"seconds":0.1])
    let key=try jsonString(["x","","","en","ru"])
    let entryBytes=key.utf8.count+output.utf8.count
    try expect(output.utf8.count>output.count,"the budget fixture must distinguish UTF-8 bytes from character count")
    var calls=0
    let tooSmall=LocalTranslationMemo(maxBytes:entryBytes-1)
    for _ in 0..<2 { _=try tooSmall.translate("x",glossary:"",context:"",source:"en",target:"ru",using:{_,_,_,_,_ in calls += 1;return output}) }
    try expect(calls==2,"an entry one UTF-8 byte over budget must remain uncached")

    let shortOutput=try jsonString(["text":"a"])
    let shortKey=try jsonString(["a","","","en","ru"])
    let shortEntryBytes=shortOutput.utf8.count+shortKey.utf8.count
    let bounded=LocalTranslationMemo(maxBytes:shortEntryBytes*2)
    for text in ["a","b","a","c","b"] {
      _=try bounded.translate(text,glossary:"",context:"",source:"en",target:"ru",using:{text,_,_,_,_ in calls += 1;return try! jsonString(["text":text])})
    }
    try expect(calls==6,"the UTF-8 byte budget must evict the least recently used entry when the next value exceeds its capacity")
    bounded.clear()
    let cleared=try jsonObject(bounded.translate("a",glossary:"",context:"",source:"en",target:"ru",using:{text,_,_,_,_ in calls += 1;return try! jsonString(["text":text])}))
    try expect(cleared["cacheHit"] as? Bool != true && calls==7,"clearing at the job boundary must force fresh inference for an earlier request")
  }

  private static func testLocalTranslationMemoUsesProductionDefaultBounds() throws {
    var entryCalls=0
    let defaultMemo=LocalTranslationMemo()
    for index in 0..<256 {
      let text=String(index)
      _=try defaultMemo.translate(text,glossary:"",context:"",source:"en",target:"ru",using:{text,_,_,_,_ in entryCalls += 1;return try! jsonString(["text":text])})
    }
    _=try defaultMemo.translate("0",glossary:"",context:"",source:"en",target:"ru",using:{_,_,_,_,_ in entryCalls += 1;return "{}"})
    _=try defaultMemo.translate("256",glossary:"",context:"",source:"en",target:"ru",using:{text,_,_,_,_ in entryCalls += 1;return try! jsonString(["text":text])})
    _=try defaultMemo.translate("0",glossary:"",context:"",source:"en",target:"ru",using:{_,_,_,_,_ in entryCalls += 1;return "{}"})
    _=try defaultMemo.translate("1",glossary:"",context:"",source:"en",target:"ru",using:{text,_,_,_,_ in entryCalls += 1;return try! jsonString(["text":text])})
    try expect(entryCalls==258,"the default cache should hold 256 entries and evict the least recently used entry")

    var byteCalls=0
    let defaultByteMemo=LocalTranslationMemo()
    let large="Ж".padding(toLength:600_000,withPad:"Ж",startingAt:0)
    for _ in 0..<2 {
      _=try defaultByteMemo.translate(large,glossary:"",context:"",source:"en",target:"ru",using:{text,_,_,_,_ in byteCalls += 1;return try! jsonString(["text":text])})
    }
    try expect(byteCalls==2,"the default 2 MiB cap should reject a UTF-8 entry whose combined key and response exceed its byte budget")
  }

  private static func testDocxAndOnlineJobMigrationAndConsent() throws {
    let fixture=try Fixture()
    let initialEngine=try fixture.store.selectedEngine()
    try expect(initialEngine["kind"]=="local" && initialEngine["id"]==ModelCatalog.recommendedId,"selectedEngine should migrate to the persisted local model by default")
    _=try fixture.store.setSelectedEngine(["kind":"online","id":"mymemory"])
    _=try fixture.store.setSelectedModelId(ModelCatalog.compactId)
    try expect(try fixture.store.selectedEngine()["kind"]=="online" && fixture.store.selectedEngine()["id"]=="mymemory","changing the local model preference must not replace a selected online engine")
    _=try fixture.store.setSelectedEngine(["kind":"local","id":ModelCatalog.balancedId])
    try expect(try fixture.store.selectedModelId()==ModelCatalog.balancedId,"explicitly selecting a local engine should keep its model selection in sync")
    let saved=try fixture.store.saveDocument(id:"document-docx",name:"source.docx",path:fixture.documentURL.path,pages:2,sourceSHA256:try sha256(at:fixture.documentURL),format:"docx")
    try expect(saved["format"] as? String=="docx" && saved["pageCount"] as? Int==0 && saved["segmentCount"] as? Int==2,"DOCX segment counts must remain separate from rendered PDF pages")
    try expectThrows("online jobs must require per-job explicit disclosure consent") {
      _=try fixture.store.addJob(id:"job-online-no-consent",input:["documentId":"document-docx","engine":["kind":"online","id":"mymemory"],"sourceLanguage":"en","targetLanguage":"ru"],pageCount:2)
    }
    let job=try fixture.store.addJob(id:"job-online",input:["documentId":"document-docx","engine":["kind":"online","id":"mymemory"],"onlineConsent":true,"sourceLanguage":"en","targetLanguage":"ru","useGlossary":true],pageCount:2)
    let engine=job["engine"] as? [String:Any]
    try expect(engine?["kind"] as? String=="online" && engine?["id"] as? String=="mymemory","online engine identity must persist independently of local model selection")
    try expect(job["documentFormat"] as? String=="docx" && job["progressUnit"] as? String=="segment","DOCX jobs must report segment progress with a singular unit")
    try expect(try fixture.store.jobGlossaryText("job-online").isEmpty,"online jobs must never snapshot or send a local glossary")
    try fixture.store.validateOnlineSnapshot("job-online")
    let fragments=[["translatedText":"first saved chunk" as Any]]
    try fixture.store.savePage(jobId:"job-online",index:0,status:"running",blocks:try jsonString([["id":"stable-paragraph","sourceText":"first paragraph","onlineFragments":fragments]]))
    let reopened=try TranslatorStore(directory:fixture.directory)
    let savedPage=try reopened.page("job-online",index:0)?["blocks"] as? [[String:Any]]
    try expect(savedPage?.first?["onlineFragments"] != nil,"completed online request fragments must survive app restart for resume")
    let legacy=try fixture.store.addJob(id:"job-legacy-pdf",input:["documentId":"document-1","modelId":ModelCatalog.recommendedId,"sourceLanguage":"en","targetLanguage":"ru"],pageCount:1)
    try expect(legacy["documentFormat"] as? String=="pdf" && (legacy["engine"] as? [String:Any])?["kind"] as? String=="local","existing document/job behavior must default to PDF and its pinned local model")
  }

  private static func testMyMemoryChunksStayUnicodeSafeAndWithinProviderLimit() throws {
    let input=String(repeating:"Hello. ",count:90)+" e\u{301} 👩‍👩‍👧‍👦\r\n"+String(repeating:"你好。",count:190)
    let chunks=try MyMemoryTranslator.chunks(input,byteLimit:500)
    try expect(!chunks.isEmpty && chunks.allSatisfy{$0.utf8.count<=500},"every MyMemory request fragment must fit the 500-byte provider limit")
    try expect(chunks.joined()==input,"request chunking must preserve all punctuation, whitespace, and Unicode characters")
    try expect(chunks.contains("\r\n") && chunks.allSatisfy{!$0.contains("\n") || $0=="\n" || $0=="\r\n"},"each request fragment must contain no more than one paragraph and preserve CRLF boundaries")
    try expect(try MyMemoryTranslator.chunks("",byteLimit:500).isEmpty,"empty text must not create an unnecessary network request")
    let oversized="a"+String(repeating:"\u{301}",count:300)
    do { _=try MyMemoryTranslator.chunks(oversized,byteLimit:500);throw TestFailure(description:"an oversized grapheme must not be split into invalid Unicode") }
    catch let failure as MyMemoryTranslator.Failure { try expect(failure == .oversizedCharacter,"an unbreakable grapheme should fail with an explicit safe error") }
  }

  private static func testMyMemoryUsesOnlyConsentScopedGetAndPausesQuotaErrors() throws {
    MockTranslationProtocol.reset(status:200,body:"{\"responseStatus\":200,\"responseData\":{\"translatedText\":\"bonjour &amp; &#x1F600;\"}}")
    let translated=try MyMemoryTranslator(protocolClasses:[MockTranslationProtocol.self]).translate("  hello  \r\n",source:"en",target:"fr")
    try expect(translated=="  bonjour & 😀  \r\n","the client should decode HTML entities and preserve paragraph edge whitespace")
    guard let request=MockTranslationProtocol.lastRequest,let url=request.url,
      let query=URLComponents(url:url,resolvingAgainstBaseURL:false)?.queryItems else{throw TestFailure(description:"The provider request should be captured by the test protocol.")}
    try expect(url.scheme=="https" && url.host=="api.mymemory.translated.net" && request.httpMethod=="GET","MyMemory must use its HTTPS GET endpoint")
    try expect(query.first(where:{$0.name=="q"})?.value=="hello" && query.first(where:{$0.name=="langpair"})?.value=="en|fr","only trimmed paragraph text and language pair should be sent")
    try expect(!query.contains(where:{["key","de","email"].contains($0.name)}),"the client must not add credentials, contribution controls, or email")

    MockTranslationProtocol.reset(status:200,body:"{\"responseStatus\":403,\"responseDetails\":\"Daily quota exceeded\"}")
    do { _=try MyMemoryTranslator(protocolClasses:[MockTranslationProtocol.self]).translate("hello",source:"en",target:"fr");throw TestFailure(description:"a quota error should pause instead of returning translation") }
    catch let failure as MyMemoryTranslator.Failure { try expect(failure == .quota,"quota errors should pause with the quota-specific safe explanation; got \(failure.errorDescription ?? "missing")") }
    MockTranslationProtocol.reset(status:429,body:"not-json")
    do { _=try MyMemoryTranslator(protocolClasses:[MockTranslationProtocol.self]).translate("hello",source:"en",target:"fr");throw TestFailure(description:"HTTP 429 must pause as quota exhaustion") }
    catch let failure as MyMemoryTranslator.Failure { try expect(failure == .quota,"HTTP 429 should be reported as exhausted quota") }
  }

  private static func testMyMemoryPreservesParagraphWhitespaceAndDecodesEntities() throws {
    MockTranslationProtocol.reset(status:200,body:"{\"responseStatus\":200,\"responseData\":{\"translatedText\":\"bonjour\"}}")
    let client=MyMemoryTranslator(protocolClasses:[MockTranslationProtocol.self])
    try expect(try client.translate(" \n \t",source:"en",target:"fr")==" \n \t","whitespace-only paragraph fragments should be preserved without a request")
    try expect(MockTranslationProtocol.lastRequest==nil,"whitespace-only fragments must not be sent to the service")
  }

  private static func testMyMemoryRecognizesQuotaFlagOnSuccessStatus() throws {
    let body=Data("{\"responseStatus\":200,\"quotaFinished\":true,\"responseData\":{\"translatedText\":\"ok\"}}".utf8)
    try expect(MyMemoryTranslator.parseResponse(body) == .failure(.quota),"quotaFinished must pause the job even when responseStatus is 200")
    let markup=try JSONSerialization.data(withJSONObject:["responseStatus":200,"responseData":["translatedText":"<g id=\"2297\">25 </g><g id=\"2298\">Нм</g> &lt;literal&gt;"]])
    try expect(MyMemoryTranslator.parseResponse(markup) == .success("25 Нм <literal>"),"known TMX group wrappers must not leak into translated document text")
    let literal=try JSONSerialization.data(withJSONObject:["responseStatus":200,"responseData":["translatedText":"&lt;g id=&quot;2297&quot;&gt;literal&lt;/g&gt; <g>ordinary</g>"]])
    try expect(MyMemoryTranslator.parseResponse(literal) == .success("<g id=\"2297\">literal</g> <g>ordinary</g>"),"escaped or unqualified group text must remain literal")
  }

  private static func testMyMemoryRejectsOversizedChunkedResponsesWithoutContentLength() throws {
    let chunks = [Data(repeating: 0x61, count: 140_000), Data(repeating: 0x62, count: 140_000)]
    MockTranslationProtocol.reset(status: 200, chunks: chunks)
    do {
      _ = try MyMemoryTranslator(protocolClasses: [MockTranslationProtocol.self])
        .translate("hello", source: "en", target: "fr")
      throw TestFailure(description: "an oversized chunked provider response must be rejected")
    } catch let failure as MyMemoryTranslator.Failure {
      try expect(failure == .invalidResponse, "the response cap must apply cumulatively when Content-Length is absent")
    }
  }

  private static func testMyMemoryRejectsOversizedDeclaredAndLyingLengthsButAcceptsBoundedBody() throws {
    MockTranslationProtocol.reset(status: 200, chunks: [Data(repeating: 0x61, count: 1)], declaredContentLength: "256001")
    do {
      _ = try MyMemoryTranslator(protocolClasses: [MockTranslationProtocol.self]).translate("hello", source: "en", target: "fr")
      throw TestFailure(description: "a provider response declaring more than the byte cap must be rejected")
    } catch let failure as MyMemoryTranslator.Failure {
      try expect(failure == .invalidResponse, "oversized declared Content-Length must fail before body accumulation")
    }

    let overflow = [Data(repeating: 0x61, count: 140_000), Data(repeating: 0x62, count: 140_000)]
    MockTranslationProtocol.reset(status: 200, chunks: overflow, declaredContentLength: "1")
    do {
      _ = try MyMemoryTranslator(protocolClasses: [MockTranslationProtocol.self]).translate("hello", source: "en", target: "fr")
      throw TestFailure(description: "a provider response lying about a small Content-Length must still be bounded")
    } catch let failure as MyMemoryTranslator.Failure {
      try expect(failure == .invalidResponse, "cumulative chunk cap must not trust Content-Length")
    }

    let valid = "{\"responseStatus\":200,\"responseData\":{\"translatedText\":\"bounded\"}}"
    MockTranslationProtocol.reset(status: 200, chunks: [Data(valid.utf8)], declaredContentLength: String(valid.utf8.count))
    let translated = try MyMemoryTranslator(protocolClasses: [MockTranslationProtocol.self]).translate("hello", source: "en", target: "fr")
    try expect(translated == "bounded", "a valid body under the byte cap should still translate")
  }

  private static func testMyMemoryCancellationCannotBeReplacedByLateProtocolData() throws {
    MockTranslationProtocol.reset(status: 200, body: "{\"responseStatus\":200,\"responseData\":{\"translatedText\":\"late success\"}}")
    MockTranslationProtocol.holdResponse = true
    let started = DispatchSemaphore(value: 0)
    let completed = DispatchSemaphore(value: 0)
    let resultLock = NSLock()
    var result: Result<String, Error>?
    MockTranslationProtocol.onStart = { started.signal() }
    let translator = MyMemoryTranslator(protocolClasses: [MockTranslationProtocol.self])
    DispatchQueue.global().async {
      let resultForRequest: Result<String, Error>
      do { resultForRequest = .success(try translator.translate("hello", source: "en", target: "fr")) }
      catch { resultForRequest = .failure(error) }
      resultLock.lock(); result = resultForRequest; resultLock.unlock()
      completed.signal()
    }
    try expect(started.wait(timeout: .now() + 3) == .success, "the URLProtocol request should start before cancellation")
    translator.cancel()
    try expect(completed.wait(timeout: .now() + 3) == .success, "canceling the active request should release its waiting translation worker")
    resultLock.lock(); let finalResult = result; resultLock.unlock()
    guard case .failure(let error)? = finalResult else { throw TestFailure(description: "a canceled request must not return a later translation") }
    try expect(error as? MyMemoryTranslator.Failure == .canceled, "the active request cancellation should be reported explicitly")
    let canceledRequest = MockTranslationProtocol.lastInstance
    MockTranslationProtocol.reset(status: 200, body: "{\"responseStatus\":200,\"responseData\":{\"translatedText\":\"new success\"}}")
    MockTranslationProtocol.holdResponse = true
    let secondStarted = DispatchSemaphore(value: 0)
    MockTranslationProtocol.onStart = { secondStarted.signal() }
    var secondResult: Result<String, Error>?
    DispatchQueue.global().async {
      let resultForRequest: Result<String, Error>
      do { resultForRequest = .success(try translator.translate("hello again", source: "en", target: "fr")) }
      catch { resultForRequest = .failure(error) }
      resultLock.lock(); secondResult = resultForRequest; resultLock.unlock()
      completed.signal()
    }
    try expect(secondStarted.wait(timeout: .now() + 3) == .success, "a fresh request should start after cancellation cleanup")
    canceledRequest?.sendLateResponse()
    MockTranslationProtocol.lastInstance?.sendLateResponse()
    try expect(completed.wait(timeout: .now() + 3) == .success, "the fresh request should finish after the canceled request sends late data")
    resultLock.lock(); let restartedResult = secondResult; resultLock.unlock()
    try expect(try restartedResult?.get() == "new success", "late data from a canceled URLSession task must not replace a newer request result")
  }

  private static func testDocxWarningsAndExpandedTranslationCheckpoint() throws {
    let fixture=try Fixture()
    let warning:[[String:Any]]=[["code":"DOCX_UNSUPPORTED_CONTENT","message":"A content control was retained without translation."]]
    _=try fixture.store.saveDocument(id:"document-warning",name:"warning.docx",path:fixture.documentURL.path,pages:1,sourceSHA256:try sha256(at:fixture.documentURL),format:"docx",warnings:warning)
    let persistedWarnings=try fixture.store.documentWarnings("document-warning")
    try expect(persistedWarnings.count==1 && persistedWarnings[0]["code"] as? String=="DOCX_UNSUPPORTED_CONTENT" && persistedWarnings[0]["message"] as? String==warning[0]["message"] as? String,"DOCX warning objects must survive the SQLite JSON round-trip")
    let job=try fixture.store.addJob(id:"job-expanded-docx",input:["documentId":"document-warning","modelId":ModelCatalog.recommendedId,"sourceLanguage":"en","targetLanguage":"ru"],pageCount:1)
    try expect((job["warnings"] as? [[String:Any]])?.count==1,"an imported DOCX warning should be copied to its translation job")

    let expanded=String(repeating:"Ж",count:1_100)
    try expect(expanded.utf8.count>1_800,"the fixture should exceed the former fixed 1,800-byte source-segment limit")
    let blocks:[[String:Any]]=[["id":"stable-paragraph","sourceText":"Short source text.","translatedText":expanded,"status":"translated"]]
    try fixture.store.savePage(jobId:"job-expanded-docx",index:0,status:"completed",blocks:try jsonString(blocks))
    let reopened=try TranslatorStore(directory:fixture.directory)
    let storedBlocks=try reopened.page("job-expanded-docx",index:0)?["blocks"] as? [[String:Any]]
    let storedTranslation=storedBlocks?.first?["translatedText"] as? String
    try expect(storedTranslation==expanded && (storedTranslation?.utf8.count ?? 0)>1_800,"a translated DOCX segment may expand beyond 1,800 UTF-8 bytes and must remain intact after app restart")
  }

  private static func testSchema6LabelsAndRatingPreferencesPreserveLegacyState() throws {
    let fixture=try Fixture()
    _=try fixture.insertJob(id:"job-schema5")
    try fixture.store.updateJob("job-schema5",status:"completed",stage:"complete",completed:1)
    let checkpoint=try jsonString([["id":"p0","sourceText":"Checkpoint survives","translatedText":"Сохранённый перевод","status":"translated"]])
    try fixture.store.savePage(jobId:"job-schema5",index:0,status:"completed",blocks:checkpoint)
    _=try fixture.store.saveGlossary("glossary-schema5",source:"bearing",target:"подшипник",sourceLanguage:"en",targetLanguage:"ru")
    try fixture.store.setSetting("theme","dark")
    try fixture.store.execute("ALTER TABLE jobs DROP COLUMN label_color")
    try fixture.store.execute("PRAGMA user_version=5")

    let migrated=try TranslatorStore(directory:fixture.directory)
    let version=try migrated.query("PRAGMA user_version").first?["user_version"] as? Int64
    try expect(version==7,"opening a schema 5 store should apply additive schemas 6 and 7")
    let job=try migrated.job("job-schema5")
    try expect(job?["status"] as? String=="completed" && job?["modelId"] as? String==ModelCatalog.recommendedId && job?["labelColor"] is NSNull,"schema migration should retain job/model state and initialize labels as null")
    try expect(try migrated.pageRows("job-schema5").count==1 && migrated.page("job-schema5",index:0)?["blocks"] != nil,"schema migration must retain translation checkpoints")
    let counters=try migrated.query("SELECT completed_blocks,total_blocks FROM pages WHERE job_id=? AND page_index=0",["job-schema5"]).first
    try expect(counters?["completed_blocks"] as? Int64==1 && counters?["total_blocks"] as? Int64==1,"schema 7 must backfill lightweight page counters from existing block checkpoints")
    try expect(try migrated.glossary().count==1 && migrated.setting("theme",fallback:"system")=="dark","schema migration must retain glossary and settings")
    try expect(try migrated.appPreferences()["appRating"] as? Int==0 && migrated.appPreferences()["librarySort"] as? String=="recent","new app preferences should default safely for existing users")

    let colored=try migrated.setJobColor("job-schema5",color:"blue")
    try expect(colored["labelColor"] as? String=="blue","completed jobs should return refreshed JSON with the persisted label color")
    try expect(try migrated.setJobColor("job-schema5",color:"")["labelColor"] is NSNull,"an empty color should clear the label and return null")
    try expectThrows("unknown label colors must be rejected") { _=try migrated.setJobColor("job-schema5",color:"black") }
    try expectThrows("missing jobs cannot receive labels") { _=try migrated.setJobColor("missing-job",color:"red") }
    _=try migrated.addJob(id:"job-running-label",input:["documentId":"document-1","modelId":ModelCatalog.recommendedId,"sourceLanguage":"en","targetLanguage":"ru"],pageCount:1)
    try expect(try migrated.setJobColor("job-running-label",color:"red")["labelColor"] as? String=="red","active jobs must allow color labels")
    try migrated.updateJob("job-running-label",status:"canceled")
    try expectThrows("canceled jobs must reject labels") { _=try migrated.setJobColor("job-running-label",color:"red") }

    var invalidPreferencesRejected=false
    do {
      try migrated.transaction {
        try migrated.setSetting("theme","light")
        _=try migrated.setAppPreferences(appRating:6,librarySort:"color")
      }
    } catch { invalidPreferencesRejected=true }
    try expect(invalidPreferencesRejected,"an invalid app rating must reject the transaction")
    try expect(migrated.setting("theme",fallback:"system")=="dark" && (try migrated.appPreferences()["appRating"] as? Int)==0 && (try migrated.appPreferences()["librarySort"] as? String)=="recent","invalid preferences must rollback the surrounding settings transaction")
    let saved=try migrated.transaction { try migrated.setAppPreferences(appRating:5,librarySort:"color") }
    try expect(saved["appRating"] as? Int==5 && saved["librarySort"] as? String=="color","valid app rating and color-sort preferences should persist")
  }

  private static func testTranslationLanguagesAndDefaults() throws {
    try expect(TranslationLanguage.allCases.count == 6, "the app should expose exactly six translation languages")
    let pairs = TranslationLanguage.allCases.flatMap { source in
      TranslationLanguage.allCases.compactMap { target in source == target ? nil : "\(source.rawValue)>\(target.rawValue)" }
    }
    try expect(pairs.count == 30 && Set(pairs).count == 30, "the six languages should form all 30 distinct ordered pairs")
    try expect(TranslationLanguage.zhHans.visionLocale == "zh-Hans", "Simplified Chinese OCR must use the Vision Simplified Chinese locale")
    let supported = [("en","en-US","English"),("ru","ru-RU","Russian"),("zh-Hans","zh-Hans","Chinese (Simplified)"),("fr","fr-FR","French"),("de","de-DE","German"),("es","es-ES","Spanish")]
    for (source,locale,name) in supported {
      for (target,_,_) in supported where source != target {
        let pair=try TranslationLanguage.pair(source:source,target:target)
        try expect(pair.0.rawValue == source && pair.1.rawValue == target, "each supported direction must retain its source and target")
        try expect(pair.0.visionLocale == locale && pair.0.displayName == name, "OCR selection and its readable error label must identify the selected source")
      }
      try expectThrows("all identical language pairs must be rejected") { _ = try TranslationLanguage.pair(source:source,target:source) }
    }
    try expectThrows("unknown target language must be rejected") { _ = try TranslationLanguage.pair(source:"en",target:"xx") }
    let fixture = try Fixture()
    let defaults = try fixture.store.languagePreferences()
    try expect(defaults.sourceLanguage == "en" && defaults.targetLanguage == "ru", "new installations should default to English to Russian")
    try expect(try fixture.store.setting("source_language", fallback: "") == "en" && fixture.store.setting("target_language", fallback: "") == "ru", "bootstrap should persist the default language pair")
    try fixture.store.setSetting("source_language", "invalid")
    try fixture.store.setSetting("target_language", "fr")
    let repaired = try fixture.store.languagePreferences()
    try expect(repaired.sourceLanguage == "en" && repaired.targetLanguage == "ru", "an invalid stored pair should be repaired to English to Russian")
    try expect(try fixture.store.setting("source_language", fallback: "") == "en" && fixture.store.setting("target_language", fallback: "") == "ru", "bootstrap should persist the repaired pair")
    try fixture.store.setLanguagePreferences(sourceLanguage: "zh-Hans", targetLanguage: "fr")
    let saved = try fixture.store.languagePreferences()
    try expect(saved.sourceLanguage == "zh-Hans" && saved.targetLanguage == "fr", "the source and target selection should persist across store instances")
    let reopened = try TranslatorStore(directory: fixture.directory).languagePreferences()
    try expect(reopened.sourceLanguage == "zh-Hans" && reopened.targetLanguage == "fr", "the source and target selection should survive reopening the private store")
    try expectThrows("identical source and target languages must be rejected") {
      try fixture.store.setLanguagePreferences(sourceLanguage: "de", targetLanguage: "de")
    }
    try expectThrows("unknown language codes must be rejected") { _ = try TranslationLanguage.pair(source: "zh", target: "ru") }
  }

  private static func testInsertedJobValidatesAgainstPinnedInputs() throws {
    let fixture = try Fixture()
    let job = try fixture.insertJob(id: "job-valid")
    let model = try ModelCatalog.get(ModelCatalog.recommendedId)

    try fixture.store.validateJobSnapshot("job-valid", model: model)
    try expect(job["sourceLanguage"] as? String == "en" && job["targetLanguage"] as? String == "ru", "the job should freeze its selected language pair")
    try fixture.store.setLanguagePreferences(sourceLanguage: "fr", targetLanguage: "de")
    let frozen = try fixture.store.job("job-valid")
    try expect(frozen?["sourceLanguage"] as? String == "en" && frozen?["targetLanguage"] as? String == "ru", "changing preferences must not alter an existing job's language pair")
    try expect(job["status"] as? String == "queued", "new job should start queued")
    try expect((job["pipelineVersion"] as? Int64) == 4, "new job should pin pipeline version 4")
    try expect((job["modelRevision"] as? String) == model.revision, "new job should pin catalog revision")
    try expect((job["modelProfile"] as? String) == model.profile, "job snapshot should expose its frozen native profile")
    try expect((try fixture.store.query("SELECT model_profile FROM jobs WHERE id=?", ["job-valid"]).first?["model_profile"] as? String) == model.profile, "new job should pin the native prompt profile")
    try expectThrows("a job must remain pinned to its originally selected model ID") {
      try fixture.store.validateJobSnapshot("job-valid", model: ModelCatalog.get(ModelCatalog.qualityId))
    }
  }

  private static func testLegacyPipelineRejectsResumeAndRetainsPageCheckpoint() throws {
    let fixture = try Fixture()
    _ = try fixture.insertJob(id: "job-stale")
    let model = try ModelCatalog.get(ModelCatalog.recommendedId)
    try fixture.store.validateJobSnapshot("job-stale", model: model)

    try fixture.store.savePage(jobId: "job-stale", index: 0, status: "translated", blocks: "[]")
    try fixture.store.execute("UPDATE jobs SET pipeline_version=3 WHERE id=?", ["job-stale"])

    try expectThrows("pipeline version 3 jobs must reject continuation after the prompt/runtime change") {
      try fixture.store.validateJobSnapshot("job-stale", model: model)
    }
    try fixture.store.validateJobForExport("job-stale", model: model)
    try expect(try fixture.store.job("job-stale") != nil, "legacy jobs should remain readable")
    let rows = try fixture.store.pageRows("job-stale")
    try expect(rows.count == 1, "the saved page row should remain after snapshot rejection")
    try expect((rows.first?["index"] as? Int64) == 0, "the retained checkpoint should refer to page zero")
    try expect(rows.first?["status"] as? String == "translated", "the retained page status should stay intact")
  }

  private static func testCatalogExposesThreeUnverifiedProfilesAndResources() throws {
    try expect(ModelCatalog.models.count == 3, "the picker should expose exactly three model choices")
    try expect(Set(ModelCatalog.models.map(\.tier)) == Set(["compact", "balanced", "quality"]), "each candidate should have one distinct relative tier")
    try expect(ModelCatalog.models.allSatisfy { !$0.qualityBenchmarked }, "no experimental model quality should be marked as verified")
    try expect(ModelCatalog.compactId == "hy-mt2-q4-k-m" && ModelCatalog.balancedId == "translategemma-4b-q4" && ModelCatalog.qualityId == "hy-mt2-7b-q4", "iOS model IDs should match Android IDs")
    try expect(ModelCatalog.models.map(\.requiredMemoryBytes) == [2_147_483_648, 3_221_225_472, 6_442_450_944], "pipeline memory estimates should be per-profile and include runtime overhead")
    try expect(ModelCatalog.models.first(where: { $0.profile == "translategemma" })?.supportsGlossary == false, "TranslateGemma should reject unsupported glossary prompting")

    let model = try ModelCatalog.get(ModelCatalog.qualityId)
    let low = MemoryPolicy.assess(model, status: "ready", downloadedBytes: model.bytes, total: 8 * 1024 * 1024 * 1024, available: 5 * 1024 * 1024 * 1024, availableDisk: 0, systemLowMemory: false)
    try expect(!low.memoryEstimateEligible && low.reason == "memory", "a model that exceeds the available per-model memory estimate should be ineligible")
    let disk = MemoryPolicy.assess(model, status: "paused", downloadedBytes: model.bytes / 2, total: 16 * 1024 * 1024 * 1024, available: 10 * 1024 * 1024 * 1024, availableDisk: 1, systemLowMemory: false)
    try expect(disk.requiredDiskBytes == UInt64(model.bytes - model.bytes / 2) + 512 * 1024 * 1024, "partial downloads should reduce remaining model disk requirements")
    let installed = MemoryPolicy.assess(model, status: "ready", downloadedBytes: model.bytes, total: 16 * 1024 * 1024 * 1024, available: 10 * 1024 * 1024 * 1024, availableDisk: 0, systemLowMemory: false)
    try expect(installed.diskEstimateEligible, "a ready model should not require free space for a second model copy")

    let allAvailable = Dictionary(uniqueKeysWithValues: ModelCatalog.models.map { candidate in
      (candidate.id, MemoryPolicy.assess(candidate, status: "ready", downloadedBytes: candidate.bytes, total: 32 * 1024 * 1024 * 1024, available: 20 * 1024 * 1024 * 1024, availableDisk: 0, systemLowMemory: false))
    })
    try expect(ModelCatalog.recommended(from: allAvailable).id == ModelCatalog.qualityId, "recommendation should prefer the highest resource-eligible tier")
    let highestIneligible = allAvailable.merging([ModelCatalog.qualityId: low]) { _, new in new }
    try expect(ModelCatalog.recommended(from: highestIneligible).id == ModelCatalog.balancedId, "recommendation should fall back to the highest eligible tier")

    let payload = model.snapshot(status: "ready", downloadedBytes: model.bytes, assessment: installed)
    try expect(payload["tier"] as? String == model.tier && payload["requiredMemoryBytes"] as? UInt64 == model.requiredMemoryBytes, "model JSON should expose tier and per-profile memory estimate")
    try expect(payload["resourceReason"] as? String == "available" && payload["licenseUrl"] as? String == model.licenseUrl, "model JSON should expose resource reason and license link")
    try expect(payload["supportsGlossary"] as? Bool == model.supportsGlossary, "model JSON should expose glossary capability")
    let compact = try ModelCatalog.get(ModelCatalog.compactId).snapshot(status: "ready", downloadedBytes: ModelCatalog.models[0].bytes, assessment: installed)
    let balanced = try ModelCatalog.get(ModelCatalog.balancedId).snapshot(status: "ready", downloadedBytes: ModelCatalog.models[1].bytes, assessment: installed)
    let quality = try ModelCatalog.get(ModelCatalog.qualityId).snapshot(status: "ready", downloadedBytes: ModelCatalog.models[2].bytes, assessment: installed)
    try expect((compact["evaluation"] as? [String: Int]) == ["acceptableCases":17,"totalCases":44], "compact snapshot should expose the reported 17/44 evaluation")
    try expect((balanced["evaluation"] as? [String: Int]) == ["acceptableCases":29,"totalCases":44], "balanced snapshot should expose the reported 29/44 evaluation")
    try expect((quality["evaluation"] as? [String: Int]) == ["acceptableCases":31,"totalCases":44], "quality snapshot should expose the confirmed 31/44 evaluation")
  }

  private static func testSelectedModelSettingIsPersistedAndValidated() throws {
    let fixture = try Fixture()
    let initial = try fixture.store.resolveSelectedModelId(recommended: ModelCatalog.balancedId, installedModelIds: [])
    try expect(initial == ModelCatalog.balancedId, "a missing selection should initialize from the current recommendation")
    try expect(try fixture.store.resolveSelectedModelId(recommended: ModelCatalog.qualityId, installedModelIds: [ModelCatalog.qualityId]) == ModelCatalog.balancedId, "bootstrap resource changes must not silently replace a valid saved choice")
    try expect(try fixture.store.setSelectedModelId(ModelCatalog.balancedId) == ModelCatalog.balancedId, "a catalog choice should be accepted")
    try expect(try fixture.store.resolveSelectedModelId(recommended: ModelCatalog.compactId, installedModelIds: []) == ModelCatalog.balancedId, "an explicit selection should remain pinned across bootstrap reads")
    try fixture.store.setSetting("selected_model_id", "removed-model")
    try expect(try fixture.store.resolveSelectedModelId(recommended: ModelCatalog.compactId, installedModelIds: [ModelCatalog.qualityId]) == ModelCatalog.qualityId, "an invalid saved selection should recover to the highest installed enabled model")
    try expect(try fixture.store.selectedModelId() == ModelCatalog.qualityId, "the repaired installed choice should be persisted")
    let eligibilityFixture = try Fixture()
    try expect(try eligibilityFixture.store.resolveSelectedModelId(recommended: ModelCatalog.compactId, installedModelIds: [ModelCatalog.compactId, ModelCatalog.qualityId]) == ModelCatalog.qualityId, "the highest installed enabled tier should seed the default even when its RAM estimate is ineligible")
    try expect(try eligibilityFixture.store.resolveSelectedModelId(recommended: ModelCatalog.compactId, installedModelIds: []) == ModelCatalog.qualityId, "the installed fallback remains pinned when the recommendation changes")
    try expectThrows("unknown model IDs must be rejected") { _ = try fixture.store.setSelectedModelId("unknown") }
  }

  private static func testUnsupportedGlossaryIsRejectedBeforeJobCreation() throws {
    let fixture = try Fixture()
    _ = try fixture.store.saveGlossary("term-1", source: "bearing", target: "подшипник", sourceLanguage: "en", targetLanguage: "ru")
    try expectThrows("a non-glossary profile must reject a job with glossary text") {
      _ = try fixture.store.addJob(id: "job-no-glossary", input: [
        "documentId": "document-1",
        "modelId": ModelCatalog.balancedId,
        "sourceLanguage": "en",
        "targetLanguage": "ru",
      ], pageCount: 1)
    }
    try expect(try fixture.store.job("job-no-glossary") == nil, "unsupported glossary must be rejected before inserting a job")
  }

  private static func testPairScopedGlossaryAndGemmaValidation() throws {
    let fixture = try Fixture()
    _ = try fixture.store.saveGlossary("term-fr-de", source: "roulement", target: "Lager", sourceLanguage: "fr", targetLanguage: "de")
    _ = try fixture.store.saveGlossary("term-en-ru", source: "bearing", target: "подшипник", sourceLanguage: "en", targetLanguage: "ru")
    try expect(try fixture.store.glossaryText(nil, sourceLanguage: "fr", targetLanguage: "de") == "roulement translates to Lager", "only glossary rows matching the requested pair should be included")
    try expect(try fixture.store.glossaryText("term-fr-de", sourceLanguage: "en", targetLanguage: "ru").isEmpty, "a selected glossary row from a different pair should not apply")
    try expectThrows("TranslateGemma must reject a matching glossary before inserting a job") {
      _ = try fixture.store.addJob(id: "job-gemma-glossary", input: [
        "documentId": "document-1", "modelId": ModelCatalog.balancedId,
        "glossaryId": "term-fr-de", "sourceLanguage": "fr", "targetLanguage": "de",
      ], pageCount: 1)
    }
    let noMatchingTerms = try fixture.store.addJob(id: "job-gemma-other-pair", input: [
      "documentId": "document-1", "modelId": ModelCatalog.balancedId,
      "glossaryId": "term-fr-de", "sourceLanguage": "en", "targetLanguage": "ru",
    ], pageCount: 1)
    try expect(noMatchingTerms["pipelineVersion"] as? Int64 == 4, "Gemma should allow a selected glossary with no entries for this pair")
    try expect(try fixture.store.jobGlossaryText("job-gemma-other-pair").isEmpty, "the frozen job glossary should contain only the matching pair")
  }

  private static func testGlossaryOptOutFreezesEmptySnapshotAndLegacyMigrationIsVersionBounded() throws {
    let fixture = try Fixture()
    _ = try fixture.store.saveGlossary("term-en-ru", source: "bearing", target: "подшипник", sourceLanguage: "en", targetLanguage: "ru")
    let defaultJob = try fixture.store.addJob(id: "job-default-glossary", input: [
      "documentId": "document-1", "modelId": ModelCatalog.recommendedId,
      "sourceLanguage": "en", "targetLanguage": "ru", "glossaryId": "term-en-ru",
    ], pageCount: 1)
    try expect(try fixture.store.jobGlossaryText("job-default-glossary") == "bearing translates to подшипник", "omitted useGlossary must keep the backward-compatible true default")
    try expect(defaultJob["pipelineVersion"] as? Int64 == 4, "new jobs retain pipeline version 4")

    _ = try fixture.store.addJob(id: "job-opt-out", input: [
      "documentId": "document-1", "modelId": ModelCatalog.recommendedId,
      "sourceLanguage": "en", "targetLanguage": "ru", "glossaryId": "term-en-ru", "useGlossary": false,
    ], pageCount: 1)
    try expect(try fixture.store.jobGlossaryText("job-opt-out").isEmpty, "explicit glossary opt-out must freeze an empty snapshot")
    try fixture.store.execute("UPDATE jobs SET glossary_snapshot='' WHERE id='job-opt-out'")
    _ = try TranslatorStore(directory: fixture.directory)
    try expect(try fixture.store.jobGlossaryText("job-opt-out").isEmpty, "opening a v4 store must never refill an intentionally empty snapshot")

    try fixture.store.execute("UPDATE jobs SET pipeline_version=3,glossary_snapshot='' WHERE id='job-default-glossary'")
    let migratedStore = try TranslatorStore(directory: fixture.directory)
    try expect(try migratedStore.jobGlossaryText("job-default-glossary") == "bearing translates to подшипник", "only a pre-v4 legacy job with an empty snapshot may be populated during migration")
  }

  private static func testLegacyEnglishRussianGlossaryMigration() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("translator-legacy-glossary-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let database = directory.appendingPathComponent("translator-private.db")
    var handle: OpaquePointer?
    try expect(sqlite3_open(database.path, &handle) == SQLITE_OK, "the legacy SQLite fixture should open")
    defer { if let handle { sqlite3_close(handle) } }
    let schema = "CREATE TABLE glossary (id TEXT PRIMARY KEY, source TEXT NOT NULL, target TEXT NOT NULL, created_at INTEGER NOT NULL); INSERT INTO glossary VALUES ('legacy-term', 'bearing', 'подшипник', 1);"
    try expect(sqlite3_exec(handle, schema, nil, nil, nil) == SQLITE_OK, "the legacy glossary table should be created")
    if let handle { sqlite3_close(handle) }
    handle = nil
    let store = try TranslatorStore(directory: directory)
    let migrated = try store.glossary().first { ($0["id"] as? String) == "legacy-term" }
    try expect(migrated?["sourceLanguage"] as? String == "en" && migrated?["targetLanguage"] as? String == "ru", "legacy glossary entries should migrate to English to Russian")
    try expect(try store.glossaryText("legacy-term", sourceLanguage: "en", targetLanguage: "ru") == "bearing translates to подшипник", "legacy terms should remain usable for the original English to Russian pair")
    try expect(try store.glossaryText("legacy-term", sourceLanguage: "fr", targetLanguage: "de").isEmpty, "migrated terms must not leak into another language pair")
  }

  private static func testMissingJobCannotValidate() throws {
    let fixture = try Fixture()
    let model = try ModelCatalog.get(ModelCatalog.recommendedId)
    try expectThrows("a missing job must not validate") {
      try fixture.store.validateJobSnapshot("missing", model: model)
    }
  }

  private static func testStaleDownloadCallbacksCannotMutateResumedOrDeletedModels() throws {
    let firstTask: UInt64 = 1
    let pauseGeneration: UInt64 = 2
    let resumedTask: UInt64 = 3
    try expect(ModelDownloadCallbackGuard.accepts(taskGeneration:firstTask,currentGeneration:firstTask,deleting:false), "the current download callback should be accepted")
    try expect(!ModelDownloadCallbackGuard.accepts(taskGeneration:firstTask,currentGeneration:pauseGeneration,deleting:false), "a canceled task completion must not overwrite the pause state")
    try expect(!ModelDownloadCallbackGuard.accepts(taskGeneration:pauseGeneration,currentGeneration:resumedTask,deleting:false), "a delayed pause callback must not overwrite a resumed download")
    try expect(!ModelDownloadCallbackGuard.accepts(taskGeneration:firstTask,currentGeneration:firstTask,deleting:true), "callbacks must not mutate model state while deletion is tombstoned")
    try expect(ModelDownloadCallbackGuard.accepts(taskGeneration:resumedTask,currentGeneration:resumedTask,deleting:false), "the resumed task callback should remain accepted")
  }

  private static func testDownloadProgressCommitCannotRaceInvalidation() throws {
    let lock=NSLock()
    var generation:UInt64=1
    var deleting=false
    var commits=0
    let entered=DispatchSemaphore(value:0),release=DispatchSemaphore(value:0),invalidated=DispatchSemaphore(value:0)
    let callbackDone=DispatchSemaphore(value:0)
    DispatchQueue.global().async {
      _ = ModelDownloadCallbackGuard.performIfCurrent(lock:lock,taskGeneration:{1},currentGeneration:{generation},isDeleting:{deleting}) {
        entered.signal()
        _ = release.wait(timeout:.now()+2)
        commits += 1
      }
      callbackDone.signal()
    }
    try expect(entered.wait(timeout:.now()+1) == .success, "the progress callback should enter its guarded commit")
    DispatchQueue.global().async {
      lock.lock();generation=2;deleting=true;lock.unlock()
      invalidated.signal()
    }
    try expect(invalidated.wait(timeout:.now()+0.02) == .timedOut, "pause/delete invalidation must wait until the guarded progress write finishes")
    release.signal()
    try expect(callbackDone.wait(timeout:.now()+1) == .success, "the in-flight progress callback should finish")
    try expect(invalidated.wait(timeout:.now()+1) == .success, "the invalidation should proceed immediately after the progress transaction")
    let accepted=ModelDownloadCallbackGuard.performIfCurrent(lock:lock,taskGeneration:{1},currentGeneration:{generation},isDeleting:{deleting}) { commits += 1 }
    try expect(!accepted && commits == 1, "a stale progress callback must not commit after pause or deletion invalidates its generation")
  }

  private static func testOperationsWaitForBackgroundTaskRestoration() throws {
    let barrier=ModelDownloadRestorationBarrier()
    var sideEffects=0
    var failedClosed=false
    do { try barrier.wait(timeout:0.01);sideEffects += 1 } catch { failedClosed=true }
    try expect(failedClosed && sideEffects == 0, "start/pause/delete preflight must fail closed while restored tasks are unresolved")
    barrier.complete()
    barrier.complete()
    var proceeded=false
    do { try barrier.wait(timeout:0.1);sideEffects += 1;proceeded=true } catch {}
    try expect(proceeded && sideEffects == 1, "operations should proceed after task restoration completes exactly once")
  }

  private static func testActiveTransferProgressIsNotReportedAsDiskStorage() throws {
    let fixture=try Fixture()
    let model=try ModelCatalog.get(ModelCatalog.compactId)
    try fixture.store.updateModel(model.id,status:"downloading",bytes:3_145_728)
    let snapshot=try fixture.store.models(totalMemory:8_589_934_592,availableMemory:4_294_967_296,availableDisk:100_000_000_000,systemLowMemory:false,modelDirectory:fixture.directory).first{$0["id"] as? String == model.id}
    try expect(snapshot?["activeTransferBytes"] as? Int64 == 3_145_728, "in-progress URLSession transfer bytes should be reported as transfer progress")
    try expect(snapshot?["storedBytes"] as? Int64 == 0, "URLSession's private temporary download file must not be claimed as measured app storage")
    try fixture.store.updateModel(model.id,status:"paused",bytes:3_145_728)
    let paused=try fixture.store.models(totalMemory:8_589_934_592,availableMemory:4_294_967_296,availableDisk:100_000_000_000,systemLowMemory:false,modelDirectory:fixture.directory).first{$0["id"] as? String == model.id}
    try expect((paused?["activeTransferBytes"] as? NSNumber)?.int64Value == 0, "paused transfer progress should not be labeled active")
  }

  private static func testReadyModelsAreReconciledWithTheirFiles() throws {
    let fixture = try Fixture()
    let model = try ModelCatalog.get(ModelCatalog.compactId)
    let files = fixture.directory.appendingPathComponent("isolated-models", isDirectory:true)
    try FileManager.default.createDirectory(at:files, withIntermediateDirectories:true)
    let installed = files.appendingPathComponent("\(model.id).gguf")
    let partial = files.appendingPathComponent("\(model.id).part")
    let resume = files.appendingPathComponent("\(model.id).resume")
    func snapshot() throws -> [String:Any] {
      let models = try fixture.store.models(totalMemory:8_589_934_592,availableMemory:4_294_967_296,availableDisk:100_000_000_000,systemLowMemory:false,modelDirectory:files)
      guard let result = models.first(where:{$0["id"] as? String == model.id}) else { throw TestFailure(description:"Model snapshot is missing") }
      return result
    }
    try fixture.store.setSetting("selectedModelId", ModelCatalog.qualityId)
    try fixture.store.updateModel(model.id,status:"ready",bytes:model.bytes,error:"old error")
    let missing = try snapshot()
    try expect(missing["status"] as? String == "not_downloaded", "missing completed model must not remain Ready")
    try expect(missing["downloadedBytes"] as? Int64 == 0, "missing file must not keep old completed-byte progress")
    try expect(try fixture.store.modelState(model.id)?["status"] as? String == "ready", "snapshot repair must not overwrite a concurrently owned database row")
    try expect(try fixture.store.modelState(model.id)?["error"] as? String == "old error", "snapshot repair must not mutate downloader-owned stored errors")

    try Data([1,2,3]).write(to:installed)
    try fixture.store.updateModel(model.id,status:"ready",bytes:model.bytes)
    try expect(try snapshot()["status"] as? String == "not_downloaded", "truncated weight must not remain Ready")
    try Data([1,2,3,4]).write(to:partial)
    try fixture.store.updateModel(model.id,status:"ready",bytes:model.bytes)
    let unfinished = try snapshot()
    try expect(unfinished["status"] as? String == "paused" && unfinished["downloadedBytes"] as? Int64 == 4, "real partial file must remain resumable with actual progress")
    try FileManager.default.removeItem(at:partial)
    try Data([5,6]).write(to:resume)
    try fixture.store.updateModel(model.id,status:"ready",bytes:20)
    let resumable = try snapshot()
    try expect(resumable["status"] as? String == "paused" && resumable["downloadedBytes"] as? Int64 == 0, "resume metadata alone cannot prove old completed payload still exists")
    try FileManager.default.removeItem(at:resume)

    // A sparse test fixture supplies only filesystem metadata here. It is not
    // a model and is never passed to inference or claimed SHA verified.
    let handle = try FileHandle(forWritingTo:installed)
    try handle.truncate(atOffset:UInt64(model.bytes))
    try handle.close()
    try fixture.store.updateModel(model.id,status:"ready",bytes:model.bytes)
    try expect(try snapshot()["status"] as? String == "ready", "previously verified Ready state with matching regular-file size must remain Ready")
    try fixture.store.updateModel(model.id,status:"downloading",bytes:20)
    try FileManager.default.removeItem(at:installed)
    try expect(try snapshot()["status"] as? String == "downloading", "reconciliation must not interfere with an active transfer")
    try fixture.store.updateModel(model.id,status:"verifying",bytes:model.bytes)
    try expect(try snapshot()["status"] as? String == "verifying", "reconciliation must not interfere with verification")
    try expect(try fixture.store.setting("selectedModelId",fallback:"") == ModelCatalog.qualityId, "file reconciliation must never switch the user's chosen model")
  }

  private static func testSQLiteTypesAndFailuresDoNotPartiallyCommit() throws {
    let fixture = try Fixture()
    let row = try fixture.store.query("SELECT 42 AS integer_value,2.5 AS real_value,'text' AS text_value,x'0102' AS blob_value,NULL AS null_value,x'' AS empty_blob,'' AS empty_text,CAST(x'410042' AS TEXT) AS embedded_nul").first!
    try expect(row["integer_value"] as? Int64 == 42, "SQLite INTEGER must retain its value")
    try expect(row["real_value"] as? Double == 2.5, "SQLite REAL must retain its fractional part")
    try expect(row["text_value"] as? String == "text", "SQLite TEXT must remain text")
    try expect(row["blob_value"] as? Data == Data([1,2]), "SQLite BLOB must retain its actual bytes")
    try expect(row["null_value"] is NSNull, "SQLite NULL must remain explicit")
    try expect(row["empty_blob"] as? Data == Data(), "SQLite empty BLOB must retain its column and remain distinct from NULL")
    try expect(row["empty_text"] as? String == "", "SQLite empty TEXT must retain its column and remain distinct from NULL")
    try expect(row["embedded_nul"] as? String == "A\0B", "SQLite TEXT must use its explicit byte length rather than truncate at an embedded NUL")
    do {
      _ = try fixture.store.query("SELECT CAST(x'80' AS TEXT) AS malformed_utf8")
      throw TestFailure(description: "invalid UTF-8 in a persisted SQLite TEXT value must fail materialization")
    } catch let error as NSError {
      try expect(error.localizedDescription.contains("invalid UTF-8") && error.localizedDescription.contains("malformed_utf8"), "invalid text errors should identify the failed SQLite column")
    }
    for (value, expected) in [(Int(7) as Any,Int64(7)),(Int64(8) as Any,Int64(8)),(NSNumber(value:9) as Any,Int64(9))] {
      try expect(try fixture.store.query("SELECT ? AS value",[value]).first?["value"] as? Int64 == expected, "integer Foundation bridging must preserve numeric values")
    }
    for sql in ["", "THIS IS NOT SQL", "SELECT abs(-9223372036854775808)"] {
      try expectThrows("query must report prepare/step errors") { _ = try fixture.store.query(sql) }
      try expectThrows("execute must report prepare/step errors") { try fixture.store.execute(sql) }
    }
    try fixture.store.setSetting("stable","original")
    try expectThrows("unsupported bound values must fail before execution") {
      try fixture.store.execute("UPDATE settings SET value=? WHERE key='stable'",[Data([1])])
    }
    try expectThrows("too many bound values must fail before execution") {
      try fixture.store.execute("UPDATE settings SET value=? WHERE key='stable'",["changed","extra"])
    }
    try expect(try fixture.store.setting("stable",fallback:"") == "original", "failed binding must not commit a partial update")
    try expectThrows("SQLite constraints must surface to the caller") {
      try fixture.store.execute("INSERT INTO settings(key,value) VALUES('stable','duplicate')")
    }
    _ = try fixture.insertJob(id: "job-corrupt-null-id")
    try fixture.store.execute("UPDATE jobs SET id=NULL WHERE id=?", ["job-corrupt-null-id"])
    do {
      _ = try fixture.store.jobs()
      throw TestFailure(description: "job listing must fail closed for a persisted NULL primary identifier")
    } catch let error as NSError {
      try expect(error.localizedDescription.contains("non-text identifier"), "a NULL job identifier should produce a meaningful history error")
    }
    try fixture.store.execute("UPDATE jobs SET id=x'0102' WHERE id IS NULL")
    do {
      _ = try fixture.store.jobs()
      throw TestFailure(description: "job listing must fail closed for a persisted BLOB primary identifier")
    } catch let error as NSError {
      try expect(error.localizedDescription.contains("non-text identifier"), "a BLOB job identifier should produce a meaningful history error")
    }
    try expectThrows("unavailable parent directory must fail opening the private database") {
      _ = try TranslatorStore(directory:fixture.directory.appendingPathComponent("missing/parent"))
    }
  }

  private static func testDocumentJobAndExportLifecycle() throws {
    let fixture = try Fixture()
    try expect(try fixture.store.documents().count == 1, "the saved document must be listed")
    try expect(try fixture.store.documentPath("document-1") == fixture.documentURL.path, "the stored source path must survive reads")
    try expect(try fixture.store.documentPath("missing") == nil, "unknown source paths must stay missing")
    try expect(try fixture.store.documentSHA256("missing") == nil, "unknown source hashes must stay missing")
    _ = try fixture.insertJob(id:"job-lifecycle")
    try fixture.store.updateJob("job-lifecycle",status:"running",stage:"translate",completed:1,currentPage:0,error:"paused for test")
    let running = try fixture.store.job("job-lifecycle")!
    try expect(running["status"] as? String == "running" && running["stage"] as? String == "translate", "job state changes must persist together")
    try expect(running["completedPages"] as? Int64 == 1 && running["currentPage"] as? Int64 == 0, "job page progress must survive reads")
    try expect(running["error"] as? String == "paused for test", "job errors must be readable")
    try fixture.store.updateJob("job-lifecycle",status:"paused",clearCurrentPage:true,clearError:true)
    let paused = try fixture.store.job("job-lifecycle")!
    try expect(paused["currentPage"] is NSNull && paused["error"] is NSNull, "cleared state must become explicit null")
    try expect(try fixture.store.jobs().count == 1, "saved jobs must be listed")
    try fixture.store.savePage(jobId:"job-lifecycle",index:0,status:"partial",blocks:"[]",error:"needs review")
    try fixture.store.saveExport("export",path:"/local/result.pdf",name:"result.pdf",sha256:"digest")
    let exported = try fixture.store.export("export")!
    try expect(exported["path"] as? String == "/local/result.pdf" && exported["sha256"] as? String == "digest", "export metadata must remain attributable to its file")
    try fixture.store.deleteExport("export")
    try expect(try fixture.store.export("export") == nil, "deleting an export must remove its metadata")
    try fixture.store.deleteJob("job-lifecycle")
    try expect(try fixture.store.pageRows("job-lifecycle").isEmpty, "deleting a job must cascade only its page checkpoints")
    try expect(try fixture.store.documents().count == 1, "deleting a job must retain the user's source document")
    _ = try fixture.insertJob(id:"job-document-delete")
    try fixture.store.deleteDocument("document-1")
    try expect(try fixture.store.documents().isEmpty && fixture.store.jobs().isEmpty, "explicit source deletion must cascade dependent jobs")
    try fixture.store.updateModel(ModelCatalog.recommendedId,status:"error",bytes:3,error:"test failure")
    try fixture.store.deleteModel(ModelCatalog.recommendedId)
    try expect(try fixture.store.modelState(ModelCatalog.recommendedId) == nil, "model metadata deletion must remain independent from document state")
    _ = try fixture.store.saveGlossary("term",source:" source ",target:" target ",sourceLanguage:"en",targetLanguage:"ru")
    try expect(try fixture.store.glossaryText("term") == "source translates to target", "specific term lookup must trim and use its saved pair")
    try fixture.store.deleteGlossary("term")
    try expect(try fixture.store.glossary().isEmpty && fixture.store.glossaryText("term").isEmpty, "deleted terms must no longer enter new snapshots")
  }

  private static func testLightweightProgressAndSharedDocumentDeletion() throws {
    let fixture=try Fixture()
    _=try fixture.insertJob(id:"job-progress-a")
    _=try fixture.insertJob(id:"job-progress-b")
    let blocks=[["status":"translated"],["status":"running"],["status":"failed"]]
    try fixture.store.savePage(jobId:"job-progress-a",index:0,status:"running",blocks:try jsonString(blocks))
    try fixture.store.setPageProgress(jobId:"job-progress-a",index:0,currentBlock:1,pageBlocks:3)
    let progress=try fixture.store.job("job-progress-a")!
    try expect(progress["currentBlock"] as? Int64==1 && progress["pageBlocks"] as? Int64==3,"the active page cursor and block count must be stored without parsing checkpoint JSON during reads")
    try expect(progress["completedBlocks"] as? Int64==2 && progress["completedPageBlocks"] as? Int64==2 && progress["totalBlocks"] as? Int64==3,"page counters must expose completed and discovered blocks in the lightweight job snapshot")
    try expect(progress["stageStartedAt"] as? Int64 != nil,"each job snapshot must include a stage timing origin")
    try fixture.store.execute("UPDATE jobs SET total_pages=2 WHERE id=?",["job-progress-a"])
    let incomplete=try fixture.store.job("job-progress-a")!
    try expect(incomplete["totalBlocks"] is NSNull && incomplete["discoveredBlocks"] as? Int64==3,"undiscovered pages must keep the document-wide block total unknown")
    try fixture.store.execute("UPDATE jobs SET total_pages=1 WHERE id=?",["job-progress-a"])
    try fixture.store.updateJob("job-progress-a",currentPage:0)
    let restartedPage=try fixture.store.job("job-progress-a")!
    try expect(restartedPage["currentBlock"] as? Int64==0 && restartedPage["pageBlocks"] as? Int64==3,"restarting a page resets its active cursor while retaining the saved checkpoint's discovered block count")
    try expect(try fixture.store.deleteJobAndReleaseUnsharedDocument("job-progress-a")==nil,"deleting one of two translations must preserve their shared source")
    try expect(try fixture.store.documentPath("document-1")==fixture.documentURL.path && fixture.store.job("job-progress-b") != nil,"a shared source and its remaining translation must survive single-job deletion")
    try expect(try fixture.store.deleteJobAndReleaseUnsharedDocument("job-progress-b")==nil,"an external source must not be returned for deletion with its final translation")
    try expect(try fixture.store.documentPath("document-1")==fixture.documentURL.path,"the final translation deletion must retain metadata for an external original")
    try expectThrows("deleting a missing translation must fail without changing unrelated sources") { _ = try fixture.store.deleteJobAndReleaseUnsharedDocument("missing-job") }
    let privateId="private-\(UUID().uuidString)"
    let privateSource=AppPaths.documents.appendingPathComponent(privateId+".pdf")
    defer { try? FileManager.default.removeItem(at:privateSource) }
    try Data("private import".utf8).write(to:privateSource)
    _=try fixture.store.saveDocument(id:privateId,name:"private.pdf",path:privateSource.path,pages:1,sourceSHA256:sha256(at:privateSource))
    _=try fixture.store.addJob(id:privateId+"-a",input:["documentId":privateId,"modelId":ModelCatalog.recommendedId,"sourceLanguage":"en","targetLanguage":"ru"],pageCount:1)
    _=try fixture.store.addJob(id:privateId+"-b",input:["documentId":privateId,"modelId":ModelCatalog.recommendedId,"sourceLanguage":"en","targetLanguage":"ru"],pageCount:1)
    try expect(try fixture.store.deleteJobAndReleaseUnsharedDocument(privateId+"-a")==nil,"deleting one private-source translation must retain the shared source")
    try expect(try fixture.store.deleteJobAndReleaseUnsharedDocument(privateId+"-b")==privateSource.path,"the final translation should release its now-unshared private source")
    let rawId="raw-\(UUID().uuidString)",rawSource=AppPaths.documents.appendingPathComponent(rawId+".pdf")
    defer { try? FileManager.default.removeItem(at:rawSource) }
    try Data("raw import".utf8).write(to:rawSource)
    let raw=try fixture.store.saveDocument(id:rawId,name:"raw.pdf",path:rawSource.path,pages:1,sourceSHA256:sha256(at:rawSource))
    try expect(raw["id"] as? String==rawId && (try fixture.store.deleteUntranslatedDocument(rawId))==rawSource.path,"an unprocessed private import can be deleted and returns its own source path")
    try expectThrows("a missing raw import cannot release a source") { _ = try fixture.store.deleteUntranslatedDocument("missing-import") }
    let externalId="external-\(UUID().uuidString)"
    _ = try fixture.store.saveDocument(id:externalId,name:"external.pdf",path:fixture.documentURL.path,pages:1,sourceSHA256:sha256(at:fixture.documentURL))
    try expectThrows("an external source must not be deleted through the raw-import action") { _ = try fixture.store.deleteUntranslatedDocument(externalId) }
    try expect(try fixture.store.documentPath(externalId)==fixture.documentURL.path,"a rejected external raw import must retain its source path")
    _=try fixture.insertJob(id:"job-history")
    try expectThrows("an import with translation history cannot be deleted as a raw document") { _=try fixture.store.deleteUntranslatedDocument("document-1") }
  }

  private static func testPageMetadataAndJsonFailuresRetainCheckpoints() throws {
    let fixture = try Fixture()
    _ = try fixture.insertJob(id:"page-job")
    defer { TranslateCoreBridge.pageMetadata = "{}" }
    try expect(try fixture.store.page("page-job",index:0) == nil, "unsaved page must not appear translated")
    try fixture.store.savePage(jobId:"page-job",index:0,status:"partial",blocks:"[{\"text\":\"source\"}]",error:"review")
    TranslateCoreBridge.pageMetadata = "{\"width\":612,\"height\":792}"
    let page = try fixture.store.page("page-job",index:0)!
    try expect(page["width"] as? Int == 612 && page["height"] as? Int == 792, "page response must retain renderer metadata")
    try expect((page["blocks"] as? [[String:Any]])?.first?["text"] as? String == "source" && page["error"] as? String == "review", "page response must retain its saved text/error")
    try fixture.store.savePage(jobId:"page-job",index:0,status:"completed",blocks:"[]")
    TranslateCoreBridge.pageMetadata = "{}"
    let withoutError = try fixture.store.page("page-job",index:0)!
    try expect(withoutError["error"] == nil && withoutError["width"] as? Int == 0 && withoutError["height"] as? Int == 0, "optional page metadata has explicit defaults")
    for metadata in ["{\"error\":\"native read failed\"}","not JSON","[]"] {
      TranslateCoreBridge.pageMetadata = metadata
      try expectThrows("invalid native metadata must not yield a successful page") { _ = try fixture.store.page("page-job",index:0) }
    }
    TranslateCoreBridge.pageMetadata = "{}"
    try fixture.store.savePage(jobId:"page-job",index:0,status:"partial",blocks:"invalid JSON")
    try expectThrows("damaged checkpoint JSON must report an error") { _ = try fixture.store.page("page-job",index:0) }
    try expect(try fixture.store.pageRows("page-job").count == 1, "read failures must not delete a durable checkpoint")
    try expect(try jsonObject("{\"ok\":true}")["ok"] as? Bool == true, "valid native dictionaries must parse")
    try expect(try jsonString(NSNull()) == "null" && jsonString("text") == "\"text\"", "JSON serialization must support valid fragments")
    try expect(try fixture.store.jobGlossaryText("missing") == "", "an unknown job cannot inherit terms from the current dictionary")
  }

  private static func testMissingPinnedInputsAndLegacySourceMigration() throws {
    let fixture = try Fixture()
    try fixture.store.setSetting("selected_model_id", "unknown")
    try expect(try fixture.store.resolveSelectedModelId(recommended:"unknown",installedModelIds:[]) == ModelCatalog.compactId, "an unavailable recommendation must recover the known compact default")
    let valid:[String:Any] = ["documentId":"document-1","modelId":ModelCatalog.compactId,"sourceLanguage":"en","targetLanguage":"ru","useGlossary":false]
    for missing in ["documentId","modelId","sourceLanguage","targetLanguage"] {
      var request=valid;request.removeValue(forKey:missing)
      try expectThrows("missing pinned job input must fail: \(missing)") { _ = try fixture.store.addJob(id:"missing-\(missing)",input:request,pageCount:1) }
    }
    for key in ["documentId","modelId"] {
      var request=valid;request[key]="unknown"
      try expectThrows("unknown pinned job input must fail: \(key)") { _ = try fixture.store.addJob(id:"unknown-\(key)",input:request,pageCount:1) }
    }
    try expect(try fixture.store.jobs().isEmpty, "rejected inputs must never create a partial durable job")
    try fixture.store.execute("UPDATE documents SET source_sha256='' WHERE id='document-1'")
    try expectThrows("unhashed source must not produce a new checkpoint") { _ = try fixture.store.addJob(id:"no-digest",input:valid,pageCount:1) }
    let migrated=try TranslatorStore(directory:fixture.directory)
    try expect(try migrated.documentSHA256("document-1") == sha256(at:fixture.documentURL), "opening a legacy store must hash its actual existing source")
    _ = try migrated.saveDocument(id:"missing-source",name:"Missing.pdf",path:fixture.directory.appendingPathComponent("missing.pdf").path,pages:1,sourceSHA256:"")
    let withMissing=try TranslatorStore(directory:fixture.directory)
    try expect(try withMissing.documentSHA256("missing-source") == "", "migration must retain an unavailable source without inventing its digest")
    _ = try withMissing.addJob(id:"legacy-unknown-model",input:valid,pageCount:1)
    try withMissing.execute("UPDATE jobs SET pipeline_version=3,model_id='unknown',model_revision='' WHERE id='legacy-unknown-model'")
    let legacy=try TranslatorStore(directory:fixture.directory)
    try expect(try legacy.job("legacy-unknown-model")?["modelRevision"] as? String == "", "unknown legacy models must remain unmodified rather than adopt a current model")
    try expectThrows("unknown legacy model snapshots must reject continuation") { try legacy.validateJobSnapshot("legacy-unknown-model",model:ModelCatalog.get(ModelCatalog.compactId)) }
  }

  private static func expect(_ condition: @autoclosure () throws -> Bool, _ message: String) throws {
    guard try condition() else { throw TestFailure(description: message) }
  }

  private static func expectThrows(_ message: String, _ operation: () throws -> Void) throws {
    do {
      try operation()
    } catch {
      return
    }
    throw TestFailure(description: message)
  }
}

private struct TestFailure: Error, CustomStringConvertible {
  let description: String
  init(description: String) { self.description = description }
}

private final class MockTranslationProtocol: URLProtocol {
  private var lateBody=Data()
  static var body=Data()
  static var chunks:[Data]?
  static var status=200
  static var lastRequest:URLRequest?
  static var declaredContentLength:String?
  static var holdResponse=false
  static var onStart:(()->Void)?
  static var lastInstance:MockTranslationProtocol?
  static var instances:[MockTranslationProtocol]=[]
  static func reset(status:Int,body:String){reset(status:status,chunks:[Data(body.utf8)],declaredContentLength:nil)}
  static func reset(status:Int,chunks:[Data],declaredContentLength:String?=nil){self.status=status;self.chunks=chunks;self.body=chunks.reduce(into:Data()){$0.append($1)};self.declaredContentLength=declaredContentLength;lastRequest=nil;holdResponse=false;onStart=nil;lastInstance=nil;instances=[]}
  override class func canInit(with request:URLRequest)->Bool{request.url?.host=="api.mymemory.translated.net"}
  override class func canonicalRequest(for request:URLRequest)->URLRequest{request}
  override func startLoading(){
    Self.lastInstance=self
    Self.instances.append(self)
    lateBody=Self.body
    Self.onStart?()
    Self.lastRequest=request
    var headers=["Content-Type":"application/json"]
    if let declaredContentLength=Self.declaredContentLength{headers["Content-Length"]=declaredContentLength}
    let response=HTTPURLResponse(url:request.url!,statusCode:Self.status,httpVersion:"HTTP/1.1",headerFields:headers )!
    client?.urlProtocol(self,didReceive:response,cacheStoragePolicy:.notAllowed)
    if Self.holdResponse{return}
    for chunk in Self.chunks ?? [Self.body] { client?.urlProtocol(self,didLoad:chunk) }
    client?.urlProtocolDidFinishLoading(self)
  }
  func sendLateResponse(){client?.urlProtocol(self,didLoad:lateBody);client?.urlProtocolDidFinishLoading(self)}
  override func stopLoading(){}
}

private final class Fixture {
  let directory: URL
  let documentURL: URL
  let store: TranslatorStore

  init() throws {
    directory = FileManager.default.temporaryDirectory
      .appendingPathComponent("translator-store-pipeline-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    documentURL = directory.appendingPathComponent("source.pdf")
    try Data("test fixture; store validation only".utf8).write(to: documentURL)
    store = try TranslatorStore(directory: directory)
    _ = try store.saveDocument(
      id: "document-1",
      name: "fixture.pdf",
      path: documentURL.path,
      pages: 1,
      sourceSHA256: sha256(at: documentURL)
    )
  }

  deinit {
    try? FileManager.default.removeItem(at: directory)
  }

  func insertJob(id: String) throws -> [String: Any] {
    try store.addJob(
      id: id,
      input: [
        "documentId": "document-1",
        "modelId": ModelCatalog.recommendedId,
        "sourceLanguage": "en",
        "targetLanguage": "ru",
      ],
      pageCount: 1
    )
  }
}
