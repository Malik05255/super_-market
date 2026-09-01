package com.malik05255.market

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Verified
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

private val InkBlue = Color(0xFF10233F)
private val ActionBlue = Color(0xFF2D63E2)
private val SoftBlue = Color(0xFFEAF0FF)
private val AppBackground = Color(0xFFF5F7FB)
private val MutedInk = Color(0xFF647083)

private val LightColors = lightColorScheme(
    primary = ActionBlue,
    onPrimary = Color.White,
    primaryContainer = SoftBlue,
    onPrimaryContainer = InkBlue,
    secondary = InkBlue,
    background = AppBackground,
    onBackground = InkBlue,
    surface = Color.White,
    onSurface = InkBlue,
    surfaceVariant = Color(0xFFF0F3F8),
    onSurfaceVariant = MutedInk,
    outlineVariant = Color(0xFFDDE3ED)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CB8FF),
    onPrimary = Color(0xFF06275F),
    primaryContainer = Color(0xFF1D3768),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFC5D4F4),
    background = Color(0xFF0C111A),
    onBackground = Color(0xFFE7ECF5),
    surface = Color(0xFF121925),
    onSurface = Color(0xFFE7ECF5),
    surfaceVariant = Color(0xFF1A2331),
    onSurfaceVariant = Color(0xFFAEB8C8),
    outlineVariant = Color(0xFF303B4C)
)

@Composable
private fun MarketTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp)
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

    val scannerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val barcode = result.data?.getStringExtra(SensitiveBarcodeScannerActivity.EXTRA_BARCODE)
            if (!barcode.isNullOrBlank()) viewModel.lookup(barcode)
        } else {
            result.data?.getStringExtra("error")?.takeIf { it.isNotBlank() }?.let(viewModel::scannerFailed)
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                AppHeader(healthy = healthy, configured = viewModel.configuredClouds)
                ScanHero(
                    onScan = {
                        scannerLauncher.launch(Intent(context, SensitiveBarcodeScannerActivity::class.java))
                    }
                )
                Result(state = state, onInfo = { info = it })
                Spacer(Modifier.height(20.dp))
            }
        }

        info?.let { ProductInfoSheet(it, onDismiss = { info = null }) }
    }
}

@Composable
private fun AppHeader(healthy: Int, configured: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.secondary
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.QrCodeScanner,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(Modifier.size(11.dp))
        Column(Modifier.weight(1f)) {
            Text("مقارن", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(
                "اعرف المنتج، ثم قارن السعر",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ConnectionPill(healthy, configured)
    }
}

@Composable
private fun ConnectionPill(healthy: Int, configured: Int) {
    val online = configured > 0 && healthy > 0
    Surface(
        shape = RoundedCornerShape(50),
        color = if (online) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (online) Icons.Outlined.CloudDone else Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.size(5.dp))
            Text(
                if (online) "متصل" else "جاهز",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ScanHero(onScan: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondary),
    ) {
        Column(Modifier.padding(20.dp)) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(15.dp),
                color = Color.White.copy(alpha = 0.12f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.QrCodeScanner, null, tint = Color.White, modifier = Modifier.size(27.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "امسح الباركود",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black
            )
            Text(
                "نحدد هوية المنتج أولًا، ثم نبحث عن أسعاره تلقائيًا.",
                color = Color.White.copy(alpha = 0.76f),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onScan,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(22.dp))
                Spacer(Modifier.size(8.dp))
                Text("ابدأ المسح", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Result(state: MarketUiState, onInfo: (ProductSnapshot) -> Unit) {
    Box(Modifier.fillMaxWidth().animateContentSize()) {
        when (state) {
            MarketUiState.Idle -> IdleCard()
            is MarketUiState.Loading -> LoadingCard(state.barcode)
            is MarketUiState.Found -> ProductResult(state, onInfo)
            is MarketUiState.NotFound -> NotFoundCard(state.barcode)
            is MarketUiState.Error -> MessageCard(
                icon = Icons.Outlined.WarningAmber,
                title = "تعذر إكمال المسح",
                body = state.message
            )
        }
    }
}

@Composable
private fun IdleCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Verified, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
            Spacer(Modifier.size(10.dp))
            Column {
                Text("الباركود للهوية، وليس للمتجر", fontWeight = FontWeight.Bold)
                Text(
                    "حتى لو لم يكن المنتج في لولو أو التميمي، نحاول معرفة اسمه أولًا ثم نبحث عن توفره.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LoadingCard(barcode: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp)
            Spacer(Modifier.size(13.dp))
            Column(Modifier.weight(1f)) {
                Text("نتعرف على المنتج…", fontWeight = FontWeight.Bold)
                Text(
                    "ثم تظهر أسعار المتاجر فور وصولها",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(barcode, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NotFoundCard(barcode: String) {
    val restricted = isRestrictedCirculationBarcode(barcode)
    MessageCard(
        icon = Icons.Outlined.Inventory2,
        title = if (restricted) "باركود داخلي" else "لم نجد هوية موثوقة بعد",
        body = if (restricted) {
            "الرقم $barcode يبدو باركود متجر أو ميزان، وليس رقم GTIN عالميًا يمكن منه تحديد المنتج خارج ذلك المتجر."
        } else {
            "بحثنا عن $barcode ولم نجد اسم منتج مؤكدًا. لن نخمن الاسم أو نعرض سعر منتج مختلف."
        }
    )
}

@Composable
private fun MessageCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.Top) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.size(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProductResult(state: MarketUiState.Found, onInfo: (ProductSnapshot) -> Unit) {
    val product = state.product
    val bestOffer = product.offers.minByOrNull { it.price }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        IdentityBadge(product)
                        Spacer(Modifier.height(9.dp))
                        Text(
                            product.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        product.nameEn?.takeIf { it.isNotBlank() && it != product.displayName }?.let {
                            Text(
                                it,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            product.barcode,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ProductMedia(product, state.allowProductMedia)
                }

                Spacer(Modifier.height(18.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(16.dp))

                Text(
                    if (bestOffer != null) "أفضل سعر متاح الآن" else product.currentPriceLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val bestPrice = bestOffer?.price ?: product.currentPrice
                val bestCurrency = bestOffer?.currency ?: product.currency
                Text(
                    formatPrice(bestPrice, bestCurrency),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
                (bestOffer?.retailer ?: product.retailer)?.let {
                    Text(it, fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = { onInfo(product) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Outlined.Info, null, Modifier.size(19.dp))
                    Spacer(Modifier.size(7.dp))
                    Text("تفاصيل المنتج", fontWeight = FontWeight.Bold)
                }
            }
        }

        if (product.offers.isNotEmpty()) {
            OffersCard(product.offers, product.barcode, state.storeMediaRetailers)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PriceStat(
                modifier = Modifier.weight(1f),
                icon = Icons.AutoMirrored.Outlined.TrendingDown,
                label = "أقل 30 يوم",
                value = product.min30d,
                currency = product.currency
            )
            PriceStat(
                modifier = Modifier.weight(1f),
                icon = Icons.AutoMirrored.Outlined.TrendingUp,
                label = "أعلى 30 يوم",
                value = product.max30d,
                currency = product.currency
            )
        }

        Text(
            resultStatus(state),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun IdentityBadge(product: ProductSnapshot) {
    val label = when {
        product.exactBarcodeMatch -> "باركود مطابق"
        product.canonicalProductId != null -> "هوية منتج مؤكدة"
        else -> "تم التعرف"
    }
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Verified, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(5.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ProductMedia(product: ProductSnapshot, allowed: Boolean) {
    val imageUrl = product.imageUrl?.takeIf { allowed && it.isNotBlank() } ?: return
    Spacer(Modifier.size(12.dp))
    Surface(
        modifier = Modifier.size(86.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = product.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(8.dp).clip(RoundedCornerShape(12.dp))
        )
    }
}

@Composable
private fun OffersCard(
    offers: List<RetailerOffer>,
    scannedBarcode: String,
    storeMediaRetailers: Set<String>
) {
    val sorted = offers.sortedBy { it.price }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Storefront, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
                Spacer(Modifier.size(7.dp))
                Text("الأسعار المتاحة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("${sorted.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(7.dp))
            sorted.forEachIndexed { index, offer ->
                OfferRow(
                    offer = offer,
                    scanned = scannedBarcode,
                    cheapest = index == 0,
                    allowStoreMedia = normalizedRetailerKey(offer.retailer) in storeMediaRetailers
                )
                if (index != sorted.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun OfferRow(
    offer: RetailerOffer,
    scanned: String,
    cheapest: Boolean,
    allowStoreMedia: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StoreMark(offer, allowStoreMedia)
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(offer.retailer, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (cheapest) {
                    Spacer(Modifier.size(7.dp))
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            "الأفضل",
                            Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            offerMatchLabel(offer, scanned)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            formatPrice(offer.price, offer.currency),
            fontWeight = FontWeight.Black,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
private fun StoreMark(offer: RetailerOffer, allowStoreMedia: Boolean) {
    val logoUrl = offer.logoUrl?.takeIf { allowStoreMedia && it.isNotBlank() }
    Surface(
        modifier = Modifier.size(42.dp),
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        if (logoUrl != null) {
            AsyncImage(
                model = logoUrl,
                contentDescription = offer.retailer,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(6.dp)
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    offer.retailer.trim().firstOrNull()?.toString().orEmpty(),
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

private fun offerMatchLabel(offer: RetailerOffer, scanned: String): String? = when {
    offer.barcode == scanned -> "نفس الباركود"
    !offer.barcode.isNullOrBlank() -> "نفس المنتج"
    offer.matchMethod == "canonical_identity" -> "مطابقة هوية المنتج"
    else -> null
}

@Composable
private fun PriceStat(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: Double?,
    currency: String
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(14.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(7.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatPrice(value, currency), fontWeight = FontWeight.Bold)
        }
    }
}

private fun resultStatus(state: MarketUiState.Found): String = when {
    state.servedFromCache && state.cloudResponses > 0 -> "ظهرت النتيجة من الذاكرة فورًا ثم تم تحديثها من المصادر"
    state.servedFromCache -> "ظهرت النتيجة فورًا من ذاكرة الهاتف"
    state.cloudResponses > 0 -> "تظهر النتائج تدريجيًا فور وصولها"
    else -> "آخر بيانات موثوقة متاحة"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductInfoSheet(product: ProductSnapshot, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Text("تفاصيل المنتج", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(4.dp))
            Text(product.displayName, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            val info = product.productInfo
            if (info == null || !info.hasUsefulData) {
                Text("لا توجد معلومات موثقة كافية لهذا المنتج حتى الآن.")
            } else {
                InfoSection(
                    Icons.Outlined.Public,
                    "بلد/مكان التصنيع",
                    listOfNotNull(info.manufacturingCountry, info.manufacturingPlaces)
                        .joinToString(" • ")
                        .ifBlank { "غير متوفر" }
                )
                InfoSection(Icons.Outlined.RestaurantMenu, "المكونات", info.ingredients ?: "غير متوفرة")
                if (info.allergens.isNotEmpty()) {
                    InfoSection(Icons.Outlined.WarningAmber, "مسببات الحساسية", info.allergens.joinToString(" • "))
                }
                if (info.positiveNotes.isNotEmpty()) {
                    InfoSection(
                        Icons.Outlined.CheckCircle,
                        "ملاحظات إيجابية",
                        info.positiveNotes.joinToString("\n• ", prefix = "• ")
                    )
                }
                if (info.cautionNotes.isNotEmpty()) {
                    InfoSection(
                        Icons.Outlined.WarningAmber,
                        "ملاحظات تحتاج الانتباه",
                        info.cautionNotes.joinToString("\n• ", prefix = "• ")
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoSection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatPrice(value: Double?, currency: String): String = when {
    value == null -> "غير متوفر"
    currency.equals("SAR", ignoreCase = true) -> String.format(java.util.Locale.US, "%.2f ر.س", value)
    else -> String.format(java.util.Locale.US, "%.2f %s", value, currency)
}
