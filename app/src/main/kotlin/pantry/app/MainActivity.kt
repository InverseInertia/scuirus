package pantry.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
        val app = application as PantryApp
        setContent { PantryRoot(vm, app.container.settings) }
    }
}
