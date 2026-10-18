import Foundation
import CryptoKit

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

  var url: URL { URL(string:"https://huggingface.co/\(repo)/resolve/\(revision)/\(fileName)")! }
  var fileURL: URL { AppPaths.models.appendingPathComponent("\(id).gguf") }
  var partialURL: URL { AppPaths.models.appendingPathComponent("\(id).part") }
  var resumeURL: URL { AppPaths.models.appendingPathComponent("\(id).resume") }

  func snapshot(status: String, downloadedBytes: Int64, error: String? = nil) -> [String: Any] {
    var value: [String: Any] = ["id":id,"label":label,"revision":revision,"bytes":bytes,"sha256":sha256,"status":status,"downloadedBytes":downloadedBytes,"verified":qualityBenchmarked,"checksumPinned":true,"translationEnabled":translationEnabled]
    if let error { value["error"] = error }
    return value
  }
}

enum ModelCatalog {
  static let recommendedId = "hy-mt2-q4-k-m"
  static let models: [ModelDefinition] = [
    ModelDefinition(id:recommendedId,label:"Hy-MT2 1.8B Q4_K_M",repo:"tencent/Hy-MT2-1.8B-GGUF",fileName:"Hy-MT2-1.8B-Q4_K_M.gguf",revision:"a0c709d9fac510f2c807aa3af52872340dc37a4a",bytes:1_133_080_448,sha256:"dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699",qualityBenchmarked:false,translationEnabled:true),
    ModelDefinition(id:"hy-mt2-iq3-xs",label:"Hy-MT2 1.8B IQ3_XS (unverified compact candidate)",repo:"mradermacher/Hy-MT2-1.8B-i1-GGUF",fileName:"Hy-MT2-1.8B.i1-IQ3_XS.gguf",revision:"9f5c7d98d8b625800775e6197e55c7ed38f2f33a",bytes:840_135_680,sha256:"f37758217de25a512e6ff7a728a3cd4fa8f4843041468889bc8a9811a2a894b5",qualityBenchmarked:false,translationEnabled:false),
    ModelDefinition(id:"hy-mt2-iq2-m",label:"Hy-MT2 1.8B IQ2_M (unverified compact candidate)",repo:"mradermacher/Hy-MT2-1.8B-i1-GGUF",fileName:"Hy-MT2-1.8B.i1-IQ2_M.gguf",revision:"9f5c7d98d8b625800775e6197e55c7ed38f2f33a",bytes:698_483_424,sha256:"917ee7286326a2881ba6f519b3e43ed41ff6b1c0db8288ff52f3fae5930080d9",qualityBenchmarked:false,translationEnabled:false),
  ]

  static func get(_ id: String) throws -> ModelDefinition {
    guard let value = models.first(where:{$0.id == id}) else { throw NSError(domain:"PdfTranslator",code:10,userInfo:[NSLocalizedDescriptionKey:"Unknown model selection."]) }
    return value
  }
}

struct MemoryRecommendation {
  let availableMemoryBytes: UInt64
  let systemLowMemoryThresholdBytes: UInt64
  let reserveBytes: UInt64
  let requiredPipelineMemoryBytes: UInt64
  let requiredDiskBytes: UInt64
  let memoryEstimateEligible: Bool
  let diskEstimateEligible: Bool
  let resourceEstimateEligible: Bool
  let recommendationAvailable: Bool
  let reason: String
}

enum MemoryPolicy {
  private static let mib: UInt64 = 1024 * 1024
  private static let minimumReserve: UInt64 = 512 * mib
  private static let pipelineEnvelope: UInt64 = 2 * 1024 * mib
  private static let diskHeadroom: UInt64 = 512 * mib

  static func recommend(total: UInt64, available: UInt64, availableDisk: UInt64, systemLowMemory: Bool) throws -> MemoryRecommendation {
    let systemLowMemoryThreshold: UInt64 = 0 // iOS exposes memory-pressure events, not Android's low-memory threshold snapshot.
    let reserve = max(minimumReserve,total * 15 / 100,systemLowMemoryThreshold)
    let q4 = try ModelCatalog.get(ModelCatalog.recommendedId)
    let requiredDisk = UInt64(q4.bytes) + diskHeadroom
    let memoryEligible = !systemLowMemory && available >= reserve + pipelineEnvelope
    let diskEligible = availableDisk >= requiredDisk
    let resourceEligible = memoryEligible && diskEligible
    let reason: String
    if systemLowMemory {
      reason = "iOS reported a memory warning since app launch; wait for memory pressure to clear or relaunch before starting the provisional Q4 pipeline."
    } else if !memoryEligible {
      reason = "Free process memory is below the provisional 2 GiB Q4 pipeline estimate plus the iOS reserve. Actual memory use is unprofiled."
    } else if !diskEligible {
      reason = "Free storage is below the Q4 model size plus the 512 MiB resumable-download reserve."
    } else {
      reason = "Resource estimate only: the 2 GiB Q4 process envelope and translation quality remain unvalidated on this device. iOS does not expose Android's low-memory threshold snapshot."
    }
    return MemoryRecommendation(
      availableMemoryBytes:available,
      systemLowMemoryThresholdBytes:systemLowMemoryThreshold,
      reserveBytes:reserve,
      requiredPipelineMemoryBytes:pipelineEnvelope,
      requiredDiskBytes:requiredDisk,
      memoryEstimateEligible:memoryEligible,
      diskEstimateEligible:diskEligible,
      resourceEstimateEligible:resourceEligible,
      recommendationAvailable:false,
      reason:reason
    )
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
