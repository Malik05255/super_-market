package com.malik05255.market

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.TrendingDown
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.malik05255.market.model.ProductInfo
import com.malik05255.market.model.ProductSnapshot
import com.malik05255.market.model.RetailerOffer

class MainActivity : ComponentActivity() {
    private val viewModel: MarketViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MarketTheme { MarketScreen(viewModel) } }
    }
}

private val MarketGreen = Color(0xFF0B6B4F)
private val MarketMint = Color(0xFFDDF4EA)
private val MarketBg = Color(0xFFF7FAF8)
private val MarketInk = Color(0xFF17211D)

private val LightColors = lightColorScheme(
    primary = MarketGreen,
    onPrimary = Color.White,
    primaryContainer = MarketMint,
    onPrimaryContainer = Color(0xFF064632),
    secondary = Color(0xFFF4A61C),
    background = MarketBg,
    onBackground = MarketInk,
    surface = Color.White,
    onSurface = MarketInk,
    surfaceVariant = Color(0xFFF0F5F2),
    onSurfaceVariant = Color(0xFF5F6F67),
    outlineVariant = Color(0xFFDCE6E1)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF76DDB6),
    onPrimary = Color(0xFF003829),
    primaryContainer = Color(0xFF0B533E),
    onPrimaryContainer = Color(0xFFB5F1D8),
    background = Color(0xFF0F1512),
    surface = Color(0xFF141C18),
    surfaceVariant = Color(0xFF1D2823),
    outlineVariant = Color(0xFF33423B)
)

@Composable
private fun MarketTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = Shapes(
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp)
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarketScreen(viewModel: MarketViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val healthy by viewModel.healthyClouds.collectAsStateWithLifecycle()
    var info by remember { mutableStateOf<ProductSnapshot?>(null) }
    val context = LocalContext.current
    val options = remember {
        GmsBarcodeScannerOptions.Builder()
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
    val scanner = remember(context, options) { GmsBarcodeScanning.getClient(context, options) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Header(healthy, viewModel.configuredClouds)
                Spacer(Modifier.height(16.dp))
                ProductImage(state)
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        scanner.startScan()
                            .addOnSuccessListener { viewModel.lookup(it.rawValue) }
                            .addOnFailureListener { viewModel.scannerFailed(it.localizedMessage) }
                    },
                    modifier = Modifier.fillMaxWidth().height(62.dp),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(25.dp))
                    Spacer(Modifier.size(10.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("اقرأ الباركود", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("قراءة سريعة ومقارنة فورية", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Result(state, onInfo = { info = it })
                Spacer(Modifier.height(28.dp))
            }
        }

        info?.let { ProductInfoSheet(it, onDismiss = { info = null }) }
    }
}

@Composable
private fun Header(healthy: Int, configured: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(50.dp), RoundedCornerShape(17.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.ShoppingBag, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(27.dp))
            }
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text("مقارن الأسعار", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Text("امسح • قارن • وفر", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (configured > 0) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CloudDone, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(5.dp))
                    Text("$healthy/$configured", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
    Spacer(Modifier.height(11.dp))
    Surface(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(7.dp))
            Text("نفس الباركود أولًا • ثم نفس المنتج • تحديث كل 12 ساعة", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProductImage(state: MarketUiState) {
    val product = (state as? MarketUiState.Found)?.product
    Card(
        Modifier.fillMaxWidth().height(260.dp),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            if (!product?.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = product?.imageUrl,
                    contentDescription = product?.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp))
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(Modifier.size(82.dp), CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Image, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("صورة المنتج", fontWeight = FontWeight.Bold)
                    Text("تظهر تلقائيًا بعد المسح", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Result(state: MarketUiState, onInfo: (ProductSnapshot) -> Unit) {
    Box(Modifier.fillMaxWidth().animateContentSize()) {
        when (state) {
            MarketUiState.Idle -> MessageCard(Icons.Outlined.QrCodeScanner, "جاهز للمسح", "وجّه الكاميرا للباركود وسنعرض المقارنة فورًا.")
            is MarketUiState.Loading -> LoadingCard(state.barcode)
            is MarketUiState.Found -> ProductResult(state, onInfo)
            is MarketUiState.NotFound -> MessageCard(Icons.Outlined.Inventory2, "المنتج غير موجود بعد", "الباركود ${state.barcode} لم يرجع نتيجة موثوقة. لن نعرض سعرًا مخمّنًا.")
            is MarketUiState.Error -> MessageCard(Icons.Outlined.WarningAmber, "تعذر إكمال القراءة", state.message)
        }
    }
}

@Composable
private fun LoadingCard(barcode: String) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
            Spacer(Modifier.size(14.dp))
            Column {
                Text("نبحث بأسرع مسار متاح", fontWeight = FontWeight.Bold)
                Text("باركود $barcode", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MessageCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(body, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProductResult(state: MarketUiState.Found, onInfo: (ProductSnapshot) -> Unit) {
    val p = state.product
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(p.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("الباركود ${p.barcode}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                Text("السعر الحالي لنفس الباركود", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatPrice(p.currentPrice, p.currency), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                p.retailer?.let { Text("في $it", fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = { onInfo(p) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Outlined.Info, null)
                    Spacer(Modifier.size(7.dp))
                    Text("معلومات المنتج", fontWeight = FontWeight.Bold)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PriceStat(Modifier.weight(1f), Icons.Outlined.TrendingDown, "أقل سعر 30 يوم", p.min30d, p.currency)
            PriceStat(Modifier.weight(1f), Icons.Outlined.TrendingUp, "أعلى سعر 30 يوم", p.max30d, p.currency)
        }

        if (p.offers.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Storefront, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(7.dp))
                        Text("أسعار السوبرماركت", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    p.offers.forEachIndexed { index, offer ->
                        OfferRow(offer, p.barcode, cheapest = index == 0)
                        if (index != p.offers.lastIndex) HorizontalDivider()
                    }
                }
            }
        }

        Text(
            when {
                state.cloudResponses > 0 -> "تم التحقق من ${state.cloudResponses} سحابات"
                state.servedFromCache -> "ظهرت النتيجة فورًا من ذاكرة الهاتف"
                else -> "آخر Snapshot متاح"
            },
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PriceStat(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: Double?, currency: String) {
    Card(modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp)) {
            Icon(icon, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(7.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatPrice(value, currency), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun OfferRow(offer: RetailerOffer, scanned: String, cheapest: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(offer.retailer, fontWeight = FontWeight.Bold)
                if (cheapest) {
                    Spacer(Modifier.size(7.dp))
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text("الأرخص", Modifier.padding(horizontal = 7.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            val match = if (offer.barcode == scanned) "نفس الباركود" else if (!offer.barcode.isNullOrBlank()) "نفس المنتج • باركود مختلف" else null
            match?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
        }
        Text(formatPrice(offer.price, offer.currency), fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductInfoSheet(product: ProductSnapshot, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Text("معلومات المنتج", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(4.dp))
            Text(product.displayName, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            val info = product.productInfo
            if (info == null || !info.hasUsefulData) {
                Text("لا توجد معلومات موثقة كافية لهذا المنتج حتى الآن.")
            } else {
                InfoSection(Icons.Outlined.Public, "بلد/مكان التصنيع", listOfNotNull(info.manufacturingCountry, info.manufacturingPlaces).joinToString(" • ").ifBlank { "غير متوفر" })
                InfoSection(Icons.Outlined.RestaurantMenu, "المكونات", info.ingredients ?: "غير متوفرة")
                if (info.allergens.isNotEmpty()) InfoSection(Icons.Outlined.WarningAmber, "مسببات الحساسية", info.allergens.joinToString(" • "))
                if (info.positiveNotes.isNotEmpty()) InfoSection(Icons.Outlined.CheckCircle, "ملاحظات إيجابية على التركيبة", info.positiveNotes.joinToString("\n• ", prefix = "• "))
                if (info.cautionNotes.isNotEmpty()) InfoSection(Icons.Outlined.WarningAmber, "ملاحظات تحتاج الانتباه", info.cautionNotes.joinToString("\n• ", prefix = "• "))
            }
            Spacer(Modifier.height(10.dp))
            Text("المعلومات الغذائية وصف للبيانات المتاحة وليست تشخيصًا طبيًا.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InfoSection(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Surface(Modifier.size(40.dp), RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
        }
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatPrice(value: Double?, currency: String): String =
    value?.let { String.format(java.util.Locale.US, "%.2f %s", it, currency) } ?: "غير متوفر"
