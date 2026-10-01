// SPDX-License-Identifier: GPL-3.0-only
//
// Copyright (C) 2026 lurixo
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, version 3.
//
// This program is distributed in the hope that it will be useful, but WITHOUT ANY
// WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
// PARTICULAR PURPOSE. See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along with
// this program. If not, see <https://www.gnu.org/licenses/>.

package com.aegis.ime.neural

import android.content.SharedPreferences
import com.aegis.ime.dict.ModelDownload
import java.io.File
import org.json.JSONObject

object NeuralModelDownload {

    const val RELEASE_URL = "https://github.com/lurixo/Aegis/releases/download/model-latest/"

    const val MANIFEST_URL = "${RELEASE_URL}aegis-model-update.json"

    const val MANIFEST_PREF = "neural_manifest"

    private const val SCHEMA = 1

    private val SHA256 = Regex("[0-9a-f]{64}")

    data class Manifest(
        val id: String,
        val spec: NeuralModelSpec,
        val fileName: String,
        val url: String,
        val bytes: Long,
        val sha256: String,
        val json: String,
    )

    data class UpdateResult(val state: ModelDownload.UpdateCheck, val manifest: Manifest? = null)

    fun parse(json: String): Manifest {
        val o = JSONObject(json)
        require(o.getInt("schema") == SCHEMA)
        val id = o.getString("id")
        val file = o.getJSONObject("file")
        val name = file.getString("name")
        val url = file.getString("url")
        val bytes = file.getLong("bytes")
        val sha256 = file.getString("sha256")
        require(id.isNotBlank())
        require(name.endsWith(".gguf") && !name.startsWith(".") && '/' !in name && '\\' !in name)
        require(url.startsWith(RELEASE_URL) && url.length > RELEASE_URL.length)
        require(bytes > 0L)
        require(SHA256.matches(sha256))
        return Manifest(id, NeuralModelSpec.parse(json), name, url, bytes, sha256, json)
    }

    fun modelDir(filesDir: File): File = File(File(filesDir, "downloaded"), "neural")

    fun modelFile(filesDir: File, manifest: Manifest): File = File(modelDir(filesDir), manifest.fileName)

    fun installed(filesDir: File, prefs: SharedPreferences): Manifest? {
        val stored = prefs.all[MANIFEST_PREF] as? String ?: return null
        val manifest = runCatching { parse(stored) }.getOrNull() ?: return null
        return manifest.takeIf { modelFile(filesDir, it).let { file -> file.isFile && file.length() == it.bytes } }
    }

    fun resolve(fetch: () -> String = { ModelDownload.fetchText(MANIFEST_URL) }): Result<Manifest> =
        runCatching { parse(fetch()) }

    fun install(
        filesDir: File,
        prefs: SharedPreferences,
        manifest: Manifest,
        onProgress: (Long, Long) -> Unit,
        url: String = manifest.url,
    ): ModelDownload.DownloadResult {
        val dest = modelFile(filesDir, manifest)
        val result = ModelDownload.download(url, dest, manifest.sha256, onProgress)
        if (!result.ok) return result
        if (dest.length() != manifest.bytes || !prefs.edit().putString(MANIFEST_PREF, manifest.json).commit()) {
            dest.delete()
            return result.copy(ok = false, validator = null, failure = ModelDownload.TransferFailure.INSTALL)
        }
        modelDir(filesDir).listFiles()?.forEach { if (it.name != manifest.fileName) it.delete() }
        return result
    }

    fun checkUpdate(
        filesDir: File,
        prefs: SharedPreferences,
        fetch: () -> String = { ModelDownload.fetchText(MANIFEST_URL) },
    ): UpdateResult {
        val json = try {
            fetch()
        } catch (e: Exception) {
            return UpdateResult(ModelDownload.run { classifyRequestFailure(e).toUpdateCheck() })
        }
        val remote = runCatching { parse(json) }.getOrElse {
            return UpdateResult(ModelDownload.UpdateCheck.PARSE_ERROR)
        }
        val current = installed(filesDir, prefs)
        if (current == null || current.sha256 != remote.sha256) {
            return UpdateResult(ModelDownload.UpdateCheck.UPDATE, remote)
        }
        if (current.fileName == remote.fileName && current.copy(json = remote.json) != remote) {
            prefs.edit().putString(MANIFEST_PREF, remote.json).commit()
        }
        return UpdateResult(ModelDownload.UpdateCheck.UP_TO_DATE)
    }

    fun delete(filesDir: File, prefs: SharedPreferences): Boolean {
        val dir = modelDir(filesDir)
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
        val forgotten = prefs.edit().remove(MANIFEST_PREF).commit()
        return forgotten && !dir.exists()
    }
}
