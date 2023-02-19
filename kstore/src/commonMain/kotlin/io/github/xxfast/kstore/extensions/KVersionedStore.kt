package io.github.xxfast.kstore.extensions

import io.github.xxfast.kstore.KStore
import io.github.xxfast.kstore.utils.FILE_SYSTEM
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

import kotlinx.serialization.json.okio.decodeFromBufferedSource as decode
import kotlinx.serialization.json.okio.encodeToBufferedSink as encode

/**
 * Creates a store with a versioned encoder and decoder
 * Note: An additional file will be written to manage metadata on the same path with `.version` suffix
 *
 * @param filePath path to the file that is managed by this store
 * @param default returns this value if the file is not found. defaults to null
 * @param enableCache maintain a cache. If set to false, it always reads from disk
 * @param serializer Serializer to use. Defaults serializer ignores unknown keys and encodes the defaults
 *
 * @return store that contains a value of type [T]
 */
@OptIn(ExperimentalSerializationApi::class)
public inline fun <reified T : @Serializable Any> storeOf(
  filePath: String,
  version: Int,
  default: T? = null,
  enableCache: Boolean = true,
  serializer: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
  crossinline migration: (version: Int?, JsonElement?) -> T? = { _, _ -> default },
): KStore<T> {
  val dataPath: Path = filePath.toPath()
  val versionPath: Path = "$filePath.version".toPath() // TODO: Save to file metadata instead

  val encoder: (T?) -> Unit = { value: T? ->
    if (value != null) {
      FILE_SYSTEM.sink(versionPath).buffer().use { serializer.encode(version, it) }
      FILE_SYSTEM.sink(dataPath).buffer().use { serializer.encode(value, it) }
    } else {
      FILE_SYSTEM.delete(versionPath)
      FILE_SYSTEM.delete(dataPath)
    }
  }

  val decoder: () -> T? = {
    try {
      serializer.decode(FILE_SYSTEM.source(dataPath).buffer())
    } catch (e: SerializationException) {
      val previousVersion: Int =
        if (FILE_SYSTEM.exists(versionPath))
          serializer.decode(FILE_SYSTEM.source(versionPath).buffer())
        else
          0

      val data: JsonElement = serializer.decode(FILE_SYSTEM.source(dataPath).buffer())
      migration(previousVersion, data)
    }
  }

  return KStore(default, enableCache, encoder, decoder)
}
