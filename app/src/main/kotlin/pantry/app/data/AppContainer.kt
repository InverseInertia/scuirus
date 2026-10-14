package pantry.app.data

import android.content.Context
import pantry.core.PantryService
import pantry.core.PantryStore
import pantry.core.Workflow
import java.io.File

/** Wires the core classes to Android storage. One instance per process. */
class AppContainer(context: Context) {
    val settings = Settings(context)
    val repoDir = File(context.filesDir, "pantry-data")
    val lookup = OpenFoodFactsClient()

    lateinit var service: PantryService
        private set
    lateinit var workflow: Workflow
        private set

    /** Null when everything worked, otherwise a message to show. Safe to call again after the settings change. */
    fun start(): String? {
        val err = JGitVersionControl.bootstrap(repoDir, settings)
        if (err != null) return "Could not open or clone the repository: $err"
        val store = PantryStore(repoDir.toPath(), settings.deviceId)
        service = PantryService(store)
        try {
            service.load()
        } catch (e: pantry.core.CodecException) {
            return "Could not read the data files: ${e.message}"
        }
        JGitVersionControl.ensureInitialCommit(repoDir, settings)
        workflow = Workflow(store, JGitVersionControl(repoDir, settings))
        return null
    }
}
