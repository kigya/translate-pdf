# Pinned OOXML dependencies

- miniz3.1.2, https://github.com/richgel999/miniz/tree/3.1.2; license COPYING.miniz.
- pugixml1.16, https://github.com/zeux/pugixml/tree/v1.16; license COPYING.pugixml.

Sources are vendored for reproducible native builds. They are third-party dependencies; application ZIP/XML limits and validation remain owned code in ../docx.cpp. No downloaded build script is executed. Exact file hashes are in manifest.json.

`miniz_export.h` is the generated-header equivalent for this static build: it defines an empty export annotation and contains no executable implementation. Its exact bytes are pinned in the same manifest.
