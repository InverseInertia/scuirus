package pantry.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import pantry.app.ui.PantryRoot
import pantry.app.ui.PantryViewModel

class MainActivity : ComponentActivity() {

    private val vm: PantryViewModel by viewModels {
        viewModelFactory { initializer { PantryViewModel(application as PantryApp) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        val app = application as PantryApp
        setContent { PantryRoot(vm, app.container.settings) }
    }
}
