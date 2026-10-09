package com.weighttrend.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Required by Health Connect: explains what the app does with health data. */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WeightTrendTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp)) {
                        Text("Как «Вес» использует данные", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Приложение записывает в Health Connect взвешивания с ваших весов: вес, процент жира и костную массу. " +
                                "Из Health Connect оно читает только шаги и сон, чтобы сравнить недели по активности и балансу энергии. " +
                                "Эти данные остаются на телефоне и никуда не отправляются.",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
