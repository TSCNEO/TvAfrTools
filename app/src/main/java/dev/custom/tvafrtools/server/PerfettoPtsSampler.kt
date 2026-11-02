package dev.custom.tvafrtools.server

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded independent Perfetto session. It never clears or stops someone else's trace. */
internal object PerfettoPtsSampler {
    data class Result(val readings: List<CodecInputTrace.Reading>, val error: String? = null, val traceBytes: Int = 0)

    fun capture(pid: Int, durationSeconds: Int = 4): Result {
        if (pid <= 0) return Result(emptyList(), "No foreground process")
        var process: Process? = null
        var reader: Thread? = null
        return try {
            val duration = durationSeconds.coerceIn(4, 8)
            // A pipe works across SELinux domains where Perfetto cannot write shell-owned files.
            process = ProcessBuilder("perfetto", "-t", "${duration}s", "-b", "2mb", "-o", "-", "video")
                .redirectErrorStream(true).start()
            val child = process
            val output = ByteArrayOutputStream()
            val oversized = AtomicBoolean(false)
            reader = Thread({
                try {
                    child.inputStream.use { stream ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > 4 * 1024 * 1024) {
                                oversized.set(true); child.destroyForcibly(); break
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                } catch (_: Exception) { }
            }, "PtsTraceReader").apply { isDaemon = true; start() }
            if (!child.waitFor(duration + 4L, TimeUnit.SECONDS)) {
                child.destroyForcibly()
                return Result(emptyList(), "Perfetto capture timed out")
            }
            reader.join(1000L)
            if (reader.isAlive || oversized.get() || child.exitValue() != 0)
                return Result(emptyList(), "Perfetto unavailable or trace too large")
            val bytes = output.toByteArray()
            if (bytes.isEmpty()) return Result(emptyList(), "No Perfetto data")
            Result(CodecInputTrace.read(bytes, pid), traceBytes = bytes.size)
        } catch (error: Exception) {
            Result(emptyList(), error.javaClass.simpleName)
        } finally {
            process?.destroy()
            reader?.join(1000L)
        }
    }
}
