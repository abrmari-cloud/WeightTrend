package com.weighttrend.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

@Composable
fun <T> StateFlow<T>.collectAsStateCompat(): State<T> = collectAsState()

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { WeightTrendTheme { AppScreen(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.onForeground()
    }

    override fun onPause() {
        vm.onBackground()
        super.onPause()
    }
}

private enum class Tab(val title: String) { HOME("Вес"), HISTORY("История"), ANALYSIS("Анализ"), SETTINGS("Настройки") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(vm: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val points by vm.points.collectAsStateCompat()
    val live by vm.live.collectAsStateCompat()
    val profile by vm.profile.collectAsStateCompat()
    val goal by vm.goal.collectAsStateCompat()
    val scale by vm.scaleAddress.collectAsStateCompat()
    val message by vm.message.collectAsStateCompat()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.messageShown() }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(tab.title) }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.HOME -> Icons.Filled.Home
                                    Tab.HISTORY -> Icons.Filled.DateRange
                                    Tab.ANALYSIS -> Icons.Filled.Info
                                    Tab.SETTINGS -> Icons.Filled.Settings
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(t.title) },
                    )
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (tab) {
            Tab.HOME -> HomeScreen(points, live, profile, goal, scale != null, { tab = Tab.SETTINGS }, m)
            Tab.HISTORY -> HistoryScreen(points, { vm.delete(it) }, { vm.addManual(it) }, m)
            Tab.ANALYSIS -> AnalysisScreen(vm, m)
            Tab.SETTINGS -> SettingsScreen(vm, m)
        }
    }
}
