import Foundation
import CryptoKit
import os

struct ModelDefinition {
  let id: String
  let label: String
  let repo: String
  let fileName: String
  let revision: String
  let bytes: Int64
  let sha256: String
  let qualityBenchmarked: Bool
  let translationEnabled: Bool
  let tier: String
  let profile: String
  let requiredMemoryBytes: UInt64
  let licenseUrl: String
  let supportsGlossary: Bool
  var acceptableTestCases: Int? = nil

  var url: URL { URL(string:"https://huggingface.co/\(repo)/resolve/\(revision)/\(fileName)")! }
  var fileURL: URL { AppPaths.models.appendingPathComponent("\(id).gguf") }
  var partialURL: URL { AppPaths.models.appendingPathComponent("\(id).part") }
  var resumeURL: URL { AppPaths.models.appendingPathComponent("\(id).resume") }

  func snapshot(status: String, downloadedBytes: Int64, error: String? = nil, assessment: ModelResourceAssessment? = nil) -> [String: Any] {
    let current = assessment ?? MemoryPolicy.assessCurrent(self, status: status, downloadedBytes: downloadedBytes)
    var value: [String: Any] = [
      "id":id,"label":label,"revision":revision,"bytes":bytes,"sha256":sha256,
      "status":status,"downloadedBytes":downloadedBytes,"verified":qualityBenchmarked,
      "checksumPinned":true,"translationEnabled":translationEnabled,"tier":tier,
      "requiredMemoryBytes":requiredMemoryBytes,"memoryEstimateEligible":current.memoryEstimateEligible,
      "diskEstimateEligible":current.diskEstimateEligible,"resourceEstimateEligible":current.resourceEstimateEligible,
      "resourceReason":current.reason,"licenseUrl":licenseUrl,
      "supportsGlossary":supportsGlossary,
    ]
    if let acceptableTestCases {
      value["evaluation"] = ["acceptableCases":acceptableTestCases,"totalCases":44]
    }
    if let error { value["error"] = error }
    return value
  }
}

enum ModelCatalog {
  static let compactId = "hy-mt2-q4-k-m"
  static let balancedId = "translategemma-4b-q4"
  static let qualityId = "hy-mt2-7b-q4"
  // Kept as a source-compatible name for legacy callers; new recommendations are device-adaptive.
  static let recommendedId = compactId
  static let models: [ModelDefinition] = [
    ModelDefinition(id:compactId,label:"Hy-MT2 1.8B Q4_K_M",repo:"tencent/Hy-MT2-1.8B-GGUF",fileName:"Hy-MT2-1.8B-Q4_K_M.gguf",revision:"a0c709d9fac510f2c807aa3af52872340dc37a4a",bytes:1_133_080_448,sha256:"dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699",qualityBenchmarked:false,translationEnabled:true,tier:"compact",profile:"hy-mt2",requiredMemoryBytes:2_147_483_648,licenseUrl:"https://www.apache.org/licenses/LICENSE-2.0",supportsGlossary:true,acceptableTestCases:17),
    ModelDefinition(id:balancedId,label:"TranslateGemma 4B Q4_K_M",repo:"bullerwins/translategemma-4b-it-GGUF",fileName:"translategemma-4b-it-Q4_K_M.gguf",revision:"7c938465a870d8624bcfa98a8e4a3510053c19a8",bytes:2_489_909_312,sha256:"7f7357c14abd9da4eb200b38b05da502cd6e10d7e1d403fbc9f78c19f3209b72",qualityBenchmarked:false,translationEnabled:true,tier:"balanced",profile:"translategemma",requiredMemoryBytes:3_221_225_472,licenseUrl:"https://ai.google.dev/gemma/terms",supportsGlossary:false,acceptableTestCases:29),
    ModelDefinition(id:qualityId,label:"Hy-MT2 7B Q4_K_M",repo:"tencent/Hy-MT2-7B-GGUF",fileName:"Hy-MT2-7B-Q4_K_M.gguf",revision:"ab8472660ac61fac25f1af43fac2599d52a8a775",bytes:4_624_648_896,sha256:"9f96256500f3fc1ab4d64336b58f52a949a95ad7516b0c229476eef782f9f77b",qualityBenchmarked:false,translationEnabled:true,tier:"quality",profile:"hy-mt2",requiredMemoryBytes:6_442_450_944,licenseUrl:"https://www.apache.org/licenses/LICENSE-2.0",supportsGlossary:true,acceptableTestCases:31),
  ]

  static func get(_ id: String) throws -> ModelDefinition {
    guard let value = models.first(where:{$0.id == id}) else { throw NSError(domain:"PdfTranslator",code:10,userInfo:[NSLocalizedDescriptionKey:"Unknown model selection."]) }
    return value
  }

  static func recommended(from assessments: [String: ModelResourceAssessment]) -> ModelDefinition {
    let tierOrder = ["compact":0,"balanced":1,"quality":2]
    return models.filter { assessments[$0.id]?.resourceEstimateEligible == true }
      .max { (tierOrder[$0.tier] ?? -1) < (tierOrder[$1.tier] ?? -1) } ?? (try! get(compactId))
  }
}

struct ModelResourceAssessment {
  let memoryEstimateEligible: Bool
  let diskEstimateEligible: Bool
  let resourceEstimateEligible: Bool
  let reason: String
  let requiredDiskBytes: UInt64
}

enum MemoryPolicy {
  private static let mib: UInt64 = 1024 * 1024
  private static let minimumReserve: UInt64 = 512 * mib
  private static let downloadHeadroom: UInt64 = 512 * mib

  static func reserve(total: UInt64) -> UInt64 { max(minimumReserve,total * 15 / 100) }

  static func assess(_ model: ModelDefinition, status: String, downloadedBytes: Int64, total: UInt64, available: UInt64, availableDisk: UInt64, systemLowMemory: Bool) -> ModelResourceAssessment {
    let enoughMemory = !systemLowMemory && available >= reserve(total: total) + model.requiredMemoryBytes
    let installed = status == "ready"
    let remainingModelBytes = installed ? 0 : UInt64(max(0, model.bytes - min(model.bytes, downloadedBytes)))
    let neededDisk = installed ? 0 : (remainingModelBytes > UInt64.max - downloadHeadroom ? UInt64.max : remainingModelBytes + downloadHeadroom)
    let enoughDisk = installed || availableDisk >= neededDisk
    let reason: String
    if !model.translationEnabled { reason = "unsupported" }
    else if systemLowMemory { reason = "lowMemory" }
    else if !enoughMemory { reason = "memory" }
    else if !enoughDisk { reason = "disk" }
    else { reason = "available" }
    let memoryEligible = model.translationEnabled && enoughMemory
    let diskEligible = model.translationEnabled && enoughDisk
    return ModelResourceAssessment(memoryEstimateEligible:memoryEligible,diskEstimateEligible:diskEligible,resourceEstimateEligible:memoryEligible && diskEligible,reason:reason,requiredDiskBytes:neededDisk)
  }

  static func assessCurrent(_ model: ModelDefinition, status: String, downloadedBytes: Int64, systemLowMemory: Bool = false) -> ModelResourceAssessment {
    let availableDisk = (try? AppPaths.models.resourceValues(forKeys:[.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage) ?? 0
    #if os(iOS)
    let availableMemory = UInt64(os_proc_available_memory())
    #else
    let availableMemory: UInt64 = 0
    #endif
    return assess(model,status:status,downloadedBytes:downloadedBytes,total:ProcessInfo.processInfo.physicalMemory,available:availableMemory,availableDisk:UInt64(max(0,availableDisk)),systemLowMemory:systemLowMemory)
  }
}

enum AppPaths {
  static let support: URL = {
    let root = FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent("TranslatePDF",isDirectory:true)
    try? FileManager.default.createDirectory(at:root,withIntermediateDirectories:true)
    var values = URLResourceValues(); values.isExcludedFromBackup = true
    var mutable = root; try? mutable.setResourceValues(values)
    return root
  }()
  static let documents = directory("documents")
  static let models = directory("models")
  static let pages = directory("pages")
  static let exports = directory("exports")
  static let thumbnails = FileManager.default.temporaryDirectory.appendingPathComponent("TranslatePDF-thumbnails",isDirectory:true)
  private static func directory(_ name:String)->URL {
    let url = support.appendingPathComponent(name,isDirectory:true)
    try? FileManager.default.createDirectory(at:url,withIntermediateDirectories:true)
    var values=URLResourceValues();values.isExcludedFromBackup=true;var copy=url;try? copy.setResourceValues(values)
    return url
  }
}

func sha256(at url: URL) throws -> String {
  let handle = try FileHandle(forReadingFrom:url); defer { try? handle.close() }
  var digest = SHA256();
  while true { let data = try handle.read(upToCount:1024*1024) ?? Data(); if data.isEmpty { break }; digest.update(data:data) }
  return digest.finalize().map { String(format:"%02x",$0) }.joined()
}
