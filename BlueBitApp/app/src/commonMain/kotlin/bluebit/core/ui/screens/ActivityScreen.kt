package bluebit.core.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import bluebit.core.ui.components.SectionHeader
import bluebit.core.ui.components.StatisticCard

@Composable
fun ActivityScreen(
    steps: String,
    distance: String,
    activeMinutes: String,
    calories: String,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionHeader(title = "Activity")

        StatisticCard(label = "Steps", value = steps, modifier = Modifier.fillMaxWidth())
        StatisticCard(label = "Distance", value = distance, modifier = Modifier.fillMaxWidth())
        StatisticCard(label = "Active Minutes", value = activeMinutes, modifier = Modifier.fillMaxWidth())
        StatisticCard(label = "Calories", value = calories, modifier = Modifier.fillMaxWidth())
    }
}
