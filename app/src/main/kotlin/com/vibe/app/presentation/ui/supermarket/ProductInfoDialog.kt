package com.vibe.app.presentation.ui.supermarket

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vibe.app.data.model.ProductSnapshot

@Composable
fun ProductInfoDialog(
    product: ProductSnapshot,
    onDismiss: () -> Unit
) {
    val info = product.productInfo

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("معلومات المنتج", fontWeight = FontWeight.Bold)
                Text(
                    product.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InfoBlock(
                    title = "بلد / مكان التصنيع",
                    body = info?.manufacturingPlaces
                        ?.takeIf { it.isNotBlank() }
                        ?: info?.manufacturingCountry?.takeIf { it.isNotBlank() }
                        ?: "غير متوفر في المصدر الحالي"
                )

                InfoBlock(
                    title = "المكونات",
                    body = info?.ingredients?.takeIf { it.isNotBlank() }
                        ?: "قائمة المكونات غير متوفرة في المصدر الحالي"
                )

                if (!info?.nutritionGrade.isNullOrBlank() || info?.novaGroup != null) {
                    val values = buildList {
                        info?.nutritionGrade?.takeIf { it.isNotBlank() }?.let {
                            add("Nutri-Score: ${it.uppercase()}")
                        }
                        info?.novaGroup?.let { add("NOVA: $it") }
                    }
                    InfoBlock("مؤشرات غذائية", values.joinToString(" • "))
                }

                if (!info?.allergens.isNullOrEmpty()) {
                    NoteCard(
                        title = "مسببات حساسية مذكورة",
                        notes = info?.allergens.orEmpty(),
                        positive = false
                    )
                }

                NoteCard(
                    title = "أبرز المزايا في التركيبة",
                    notes = info?.positiveNotes.orEmpty(),
                    positive = true
                )

                NoteCard(
                    title = "أبرز الملاحظات على التركيبة",
                    notes = info?.cautionNotes.orEmpty(),
                    positive = false
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "الملاحظات معلوماتية ومبنية على بيانات الملصق الغذائي والمكونات المتاحة. لا يتم تخمين بلد الصنع أو المكونات المفقودة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                info?.dataSource?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = "مصدر بيانات المنتج: $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("إغلاق")
            }
        }
    )
}

@Composable
private fun InfoBlock(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NoteCard(
    title: String,
    notes: List<String>,
    positive: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (positive) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Text(title, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            if (notes.isEmpty()) {
                Text(
                    "لا توجد بيانات كافية لإعطاء ملاحظة مؤكدة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                notes.take(5).forEach { note ->
                    Text("• $note", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
