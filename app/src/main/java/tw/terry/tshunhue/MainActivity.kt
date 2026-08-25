package tw.terry.tshunhue

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import tw.terry.tshunhue.ui.TshunhueApp
import tw.terry.tshunhue.ui.TshunhueViewModel
import tw.terry.tshunhue.ui.theme.TshunhueTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TshunhueTheme { TshunhueApp(viewModel<TshunhueViewModel>()) } }
    }
}
