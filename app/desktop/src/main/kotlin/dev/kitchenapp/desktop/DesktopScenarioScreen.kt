package dev.kitchenapp.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kitchenapp.core.engine.matching.CandidateStatus
import dev.kitchenapp.core.engine.matching.RecipeMatch
import dev.kitchenapp.testkit.kitchen.Slice8KitchenSimulation

@Composable
internal fun DesktopScenarioScreen() {
    var runId by remember { mutableIntStateOf(0) }
    val report = remember(runId) { runDesktopScenario() }
    val ingredientNames = remember {
        Slice8KitchenSimulation.input.recipes
            .flatMap { recipe -> recipe.ingredients.map { it.ingredientId.toString() to it.rawLine.substringAfterLast(" ") } }
            .toMap()
    }
    val includedCount = report.matches.count { it.status == CandidateStatus.INCLUDED }
    val excludedCount = report.matches.size - includedCount

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Kitchen scenario", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Deterministic sample data from the shared matching testkit. " +
                        "Running it does not change your saved kitchen.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(onClick = { runId++ }) { Text("Run scenario") }
        }
        Text(
            "${report.matches.size} recipes · $includedCount included · $excludedCount excluded",
            modifier = Modifier.padding(vertical = 12.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(report.matches, key = { it.recipe.id.toString() }) { match ->
                ScenarioRecipeCard(match)
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Shopping gaps", style = MaterialTheme.typography.titleMedium)
                        if (report.shoppingLines.isEmpty()) {
                            Text("No missing ingredients in this scenario.")
                        } else {
                            report.shoppingLines.forEach { line ->
                                val name = ingredientNames[line.ingredientId.toString()] ?: line.ingredientId.toString()
                                val amount = line.quantity?.let { quantity ->
                                    buildString {
                                        append(quantity)
                                        line.unit?.let { append(" ").append(it.code) }
                                    }
                                } ?: "Amount unknown"
                                Text("$name · $amount")
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun ScenarioRecipeCard(match: RecipeMatch) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(match.recipe.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "${match.status} · readiness ${match.readiness} · score ${match.score.total}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (match.exclusions.isNotEmpty()) {
                Text("Excluded by: ${match.exclusions.joinToString()}")
            }
            match.ingredients.forEach { ingredient ->
                Text(
                    "${ingredient.requirement.rawLine}: ${ingredient.quantity.status}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            match.equipment.reasons.forEach { reason ->
                Text(
                    "Equipment ${reason.requested}: ${reason.status}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
