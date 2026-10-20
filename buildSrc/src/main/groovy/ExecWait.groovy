import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/**
 * Spawns a (long-running) server process and blocks only until it prints its "ready" line, then keeps
 * draining its stdout on a daemon thread so its pipe buffer never fills and hangs it mid-test.
 *
 * Shared by the two e2e suites - frontcache-tests (Community) and frontcache-pro-tests (the same
 * topology with the Pro jars) - which is why it is here and not in either build script.
 */
class ExecWait extends DefaultTask {
	@Internal String command
	@Internal String ready
	@Internal String directory

	@TaskAction
	def spawnProcess() {

		ProcessBuilder builder = new ProcessBuilder(command.split(' '))
		builder.redirectErrorStream(true)
		builder.directory(new File(directory))
		Process process = builder.start()

		InputStream stdout = process.getInputStream()
		BufferedReader reader = new BufferedReader(new InputStreamReader(stdout))

		def line
		while ((line = reader.readLine()) != null) {
			println line
			if (line.contains(ready)) {
				println "$command is ready"
				break
			}
		}

		Thread.startDaemon {
			def l
			while ((l = reader.readLine()) != null) {
				println l
			}
		}
	}
}
