package com.vibe.app.presentation.ui.supermarket

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.google.android.gms.mlkit.barcode.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.vibe.app.data.model.RetailerOffer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuperMarketScreen(
    viewModel: SuperMarketViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val healthyClouds by viewModel.healthyClouds.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scannerOptions = remember {
        com.google.android.gms.mlkit.barcode.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_128
            )
            .enableAutoZoom()
            .build()
    }
    val scanner = remember(context, scannerOptions) {
        GmsBarcodeScanning.getClient(context, scannerOptions)
    }

    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "سعر المنتج",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "امسح الباركود وشاهد أسعار المنتج في المتاجر",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "مزامنة الأسعار مجدولة كل 12 ساعة",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (viewModel.configuredClouds > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "السحابات الجاهزة: $healthyClouds من ${viewModel.configuredClouds}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(Modifier.height(22.dp))

                ProductImageCard(state)

                Spacer(Modifier.height(18.dp))

                Button(
                    onClick = {
                        scanner.startScan()
                            .addOnSuccessListener { barcode -> viewModel.lookupBarcode(barcode.rawValue) }
                            .addOnFailureListener { error -> viewModel.scannerFailed(error.localizedMessage) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.size(10.dp))
                    Text("اقرأ الباركود", fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(20.dp))
                ResultPanel(state)
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun ProductImageCard(state: SuperMarketUiState) {
    val product = (state as? SuperMarketUiState.Found)?.product

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (!product?.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = product?.imageUrl,
                    contentDescription = product?.displayName,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(18.dp)
                        .clip(RoundedCornerShape(22.dp))
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.Image,
                        contentDescription = null,
                        modifier = Modifier.size(68.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "صورة المنتج ستظهر هنا",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultPanel(state: SuperMarketUiState) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 2.dp
    ) {
        when (state) {
            SuperMarketUiState.Idle -> HintContent()
            is SuperMarketUiState.Loading -> LoadingContent(state.barcode)
            is SuperMarketUiState.Found -> ProductResult(state)
            is SuperMarketUiState.NotFound -> MessageContent(
                title = "المنتج غير موجود بعد",
                body = "الباركود ${state.barcode} لم يرجع نتيجة موثوقة من السحابات المتصلة."
            )
            is SuperMarketUiState.ConfigurationMissing -> MessageContent(
                title = "السحابات غير مربوطة بعد",
                body = "قارئ الباركود يعمل. بقي ربط السحابات المجانية لتفعيل الأسعار الحقيقية."
            )
            is SuperMarketUiState.Error -> MessageContent(
                title = "تعذر إكمال القراءة",
                body = state.message
            )
        }
    }
}

@Composable
private fun HintContent() {
    Column(
        modifier = Modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("اضغط «اقرأ الباركود» ثم وجّه الكاميرا إلى الرمز.")
    }
}

@Composable
private fun LoadingContent(barcode: String) {
    Row(
        modifier = Modifier.padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        Column {
            Text("جارٍ قراءة أسرع نسخة متاحة...", fontWeight = FontWeight.Bold)
            Text(barcode, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProductResult(state: SuperMarketUiState.Found) {
    val product = state.product
    Column(modifier = Modifier.padding(20.dp)) {
        Text(
            text = product.displayName,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "باركود ${product.barcode}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(18.dp))

        Text("أحدث سعر مرصود", color = MaterialTheme.colorScheme.onSurfaceVariant)
        AnimatedContent(targetState = product.currentPrice, label = "current-price") { price ->
            Text(
                text = price?.let { String.format("%.2f %s", it, product.currency) } ?: "غير متوفر",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black
            )
        }
        product.retailer?.let {
            Text("في $it", fontWeight = FontWeight.SemiBold)
        }
        product.priceUpdatedAt?.let {
            Text(
                "آخر تغير للسعر: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(18.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PriceStat(
                modifier = Modifier.weight(1f),
                label = "أقل سعر خلال 30 يوم",
                value = product.min30d,
                currency = product.currency
            )
            PriceStat(
                modifier = Modifier.weight(1f),
                label = "أعلى سعر خلال 30 يوم",
                value = product.max30d,
                currency = product.currency
            )
        }

        if (product.offers.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Text(
                text = "أسعار السوبرماركت",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "مرتبة من الأرخص إلى الأعلى",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            product.offers.forEachIndexed { index, offer ->
                RetailerPriceRow(offer)
                if (index != product.offers.lastIndex) {
                    HorizontalDivider()
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.CloudDone,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = when {
                    state.cloudResponses > 0 -> "استجابت ${state.cloudResponses} من ${state.cloudTotal} سحابات"
                    state.servedFromCache -> "ظهر فورًا من ذاكرة الهاتف — جارٍ التحقق من السحابات"
                    else -> "جارٍ التحقق من السحابات"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RetailerPriceRow(offer: RetailerOffer) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(offer.retailer, fontWeight = FontWeight.SemiBold)
            offer.updatedAt?.let {
                Text(
                    text = "آخر تغير للسعر $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = String.format("%.2f %s", offer.price, offer.currency),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun PriceStat(
    modifier: Modifier,
    label: String,
    value: Double?,
    currency: String
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = value?.let { String.format("%.2f %s", it, currency) } ?: "—",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun MessageContent(title: String, body: String) {
    Column(
        modifier = Modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
