package pantry.app

import android.app.Application
import pantry.app.data.AppContainer

class PantryApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
