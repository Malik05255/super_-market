package com.vibe.app.presentation.ui.supermarket

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.google.android.gms.mlkit.barcode.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.vibe.app.data.model.ProductInfo
import com.vibe.app.data.model.ProductSnapshot
import com.vibe.app.data.model.RetailerOffer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuperMarketScreen(
    viewModel: SuperMarketViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val healthyClouds by viewModel.healthyClouds.collectAsStateWithLifecycle()
    var infoProduct by remember { mutableStateOf<ProductSnapshot?>(null) }
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

    SuperMarketTheme {
        CompositionLocalProvider(
            androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Rtl
        ) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background
            ) { paddingValues ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MarketHeader(
                        healthyClouds = healthyClouds,
                        configuredClouds = viewModel.configuredClouds
                    )

                    Spacer(Modifier.height(18.dp))
                    ProductHeroCard(state)
                    Spacer(Modifier.height(14.dp))

                    Button(
                        onClick = {
                            scanner.startScan()
                                .addOnSuccessListener { barcode ->
                                    viewModel.lookupBarcode(barcode.rawValue)
                                }
                                .addOnFailureListener { error ->
                                    viewModel.scannerFailed(error.localizedMessage)
                                }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(62.dp),
                        shape = RoundedCornerShape(20.dp),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 2.dp,
                            pressedElevation = 0.dp
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(25.dp)
                        )
                        Spacer(Modifier.size(10.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "اقرأ الباركود",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "قراءة سريعة ومقارنة فورية",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    ResultPanel(
                        state = state,
                        onProductInfo = { infoProduct = it }
                    )
                    Spacer(Modifier.height(30.dp))
                }
            }

            infoProduct?.let { product ->
                ProductInfoBottomSheet(
                    product = product,
                    onDismiss = { infoProduct = null }
                )
            }
        }
    }
}

@Composable
private fun MarketHeader(
    healthyClouds: Int,
    configuredClouds: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Surface(
                modifier = Modifier.size(50.dp),
                shape = RoundedCornerShape(17.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.ShoppingBag,
                        contentDescription = null,
                        modifier = Modifier.size(27.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Column {
                Text(
                    text = "مقارن الأسعار",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "امسح • قارن • وفر",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (configuredClouds > 0) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudDone,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.size(5.dp))
                    Text(
                        text = "$healthyClouds/$configuredClouds",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(7.dp))
            Text(
                text = "نفس الباركود أولًا • ثم نفس المنتج • الأسعار تتحدث كل 12 ساعة",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProductHeroCard(state: SuperMarketUiState) {
    val product = (state as? SuperMarketUiState.Found)?.product

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(278.dp),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (!product?.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = product?.imageUrl,
                    contentDescription = product?.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(24.dp))
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        modifier = Modifier.size(88.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Image,
                                contentDescription = null,
                                modifier = Modifier.size(42.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "صورة المنتج",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "ستظهر تلقائيًا بعد قراءة الباركود",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (product != null) {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart),
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "تم التعرف على المنتج",
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultPanel(
    state: SuperMarketUiState,
    onProductInfo: (ProductSnapshot) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        when (state) {
            SuperMarketUiState.Idle -> IdleCard()
            is SuperMarketUiState.Loading -> LoadingCard(state.barcode)
            is SuperMarketUiState.Found -> ProductResult(state, onProductInfo)
            is SuperMarketUiState.NotFound -> MessageCard(
                icon = Icons.Outlined.Inventory2,
                title = "المنتج غير موجود بعد",
                body = "الباركود ${state.barcode} لم يرجع نتيجة موثوقة. لن نعرض سعرًا مخمّنًا."
            )
            is SuperMarketUiState.ConfigurationMissing -> MessageCard(
                icon = Icons.Outlined.CloudDone,
                title = "السحابات غير مربوطة بعد",
                body = "قارئ الباركود جاهز. بقي ربط قواعد البيانات لتفعيل الأسعار الحقيقية."
            )
            is SuperMarketUiState.Error -> MessageCard(
                icon = Icons.Outlined.WarningAmber,
                title = "تعذر إكمال القراءة",
                body = state.message
            )
        }
    }
}

@Composable
private fun IdleCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(15.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.QrCodeScanner,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Column {
                Text("جاهز للمسح", fontWeight = FontWeight.Bold)
                Text(
                    "وجّه الكاميرا للباركود وسنعرض المقارنة فورًا.",
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
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(34.dp),
                strokeWidth = 3.dp
            )
            Spacer(Modifier.size(14.dp))
            Column {
                Text(
                    "نبحث بأسرع مسار متاح",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "باركود $barcode",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ProductResult(
    state: SuperMarketUiState.Found,
    onProductInfo: (ProductSnapshot) -> Unit
) {
    val product = state.product

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = product.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "الباركود المقروء ${product.barcode}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.size(10.dp))
                    Surface(
                        shape = RoundedCornerShape(13.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.padding(9.dp).size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "أحدث سعر للباركود المقروء",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        AnimatedContent(
                            targetState = product.currentPrice,
                            label = "current-price"
                        ) { price ->
                            Text(
                                text = price?.let {
                                    String.format("%.2f %s", it, product.currency)
                                } ?: "غير متوفر",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        product.retailer?.let {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "في $it",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        product.priceUpdatedAt?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "آخر تغير للسعر: $it",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = { onProductInfo(product) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Outlined.Info, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("معلومات المنتج", fontWeight = FontWeight.Bold)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PriceStatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.TrendingDown,
                label = "أقل سعر\n30 يوم",
                value = product.min30d,
                currency = product.currency,
                positive = true
            )
            PriceStatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.TrendingUp,
                label = "أعلى سعر\n30 يوم",
                value = product.max30d,
                currency = product.currency,
                positive = false
            )
        }

        if (product.offers.isNotEmpty()) {
            OffersCard(product)
        }

        CloudStatusCard(state)
    }
}

@Composable
private fun PriceStatCard(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: Double?,
    currency: String,
    positive: Boolean
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(15.dp)) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(11.dp),
                color = if (positive) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                        tint = if (positive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondary
                        }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = value?.let { String.format("%.2f %s", it, currency) } ?: "—",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black
            )
        }
    }
}

@Composable
private fun OffersCard(product: ProductSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Storefront,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                Spacer(Modifier.size(10.dp))
                Column {
                    Text(
                        text = "أسعار نفس المنتج",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "مرتبة من الأرخص إلى الأعلى",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            product.offers.forEachIndexed { index, offer ->
                RetailerPriceRow(
                    offer = offer,
                    scannedBarcode = product.barcode,
                    cheapest = index == 0
                )
                if (index != product.offers.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun RetailerPriceRow(
    offer: RetailerOffer,
    scannedBarcode: String,
    cheapest: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(13.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Storefront,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = offer.retailer,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (cheapest) {
                    Spacer(Modifier.size(7.dp))
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "الأرخص",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            val barcodeLabel = when {
                offer.barcode.isNullOrBlank() -> null
                offer.barcode == scannedBarcode -> "نفس الباركود"
                else -> "نفس المنتج • باركود مختلف"
            }
            barcodeLabel?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            offer.updatedAt?.let {
                Text(
                    text = "آخر تحديث $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.size(8.dp))
        Text(
            text = String.format("%.2f %s", offer.price, offer.currency),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black
        )
    }
}

@Composable
private fun CloudStatusCard(state: SuperMarketUiState.Found) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudDone,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(7.dp))
            Text(
                text = when {
                    state.cloudResponses > 0 ->
                        "تم التحقق من ${state.cloudResponses} من ${state.cloudTotal} سحابات"
                    state.servedFromCache ->
                        "ظهر من ذاكرة الهاتف فورًا • جارٍ التحقق السحابي"
                    else -> "جارٍ التحقق من السحابات"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MessageCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                modifier = Modifier.size(54.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(27.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductInfoBottomSheet(
    product: ProductSnapshot,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val info = product.productInfo

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(15.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "معلومات المنتج",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        product.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            InfoSection(
                icon = Icons.Outlined.Public,
                title = "بلد ومكان التصنيع",
                body = manufacturingText(info)
            )

            Spacer(Modifier.height(10.dp))
            InfoSection(
                icon = Icons.Outlined.RestaurantMenu,
                title = "المكونات",
                body = info?.ingredients?.takeIf { it.isNotBlank() }
                    ?: "المكونات غير متوفرة في المصدر الحالي."
            )

            if (!info?.allergens.isNullOrEmpty()) {
                Spacer(Modifier.height(10.dp))
                InfoSection(
                    icon = Icons.Outlined.WarningAmber,
                    title = "مسببات الحساسية",
                    body = info!!.allergens.joinToString(" • ")
                )
            }

            if (!info?.positiveNotes.isNullOrEmpty()) {
                Spacer(Modifier.height(10.dp))
                NotesSection(
                    icon = Icons.Outlined.CheckCircle,
                    title = "نقاط إيجابية في التركيبة",
                    notes = info!!.positiveNotes,
                    positive = true
                )
            }

            if (!info?.cautionNotes.isNullOrEmpty()) {
                Spacer(Modifier.height(10.dp))
                NotesSection(
                    icon = Icons.Outlined.WarningAmber,
                    title = "ملاحظات على التركيبة",
                    notes = info!!.cautionNotes,
                    positive = false
                )
            }

            if (!info?.nutritionGrade.isNullOrBlank() || info?.novaGroup != null) {
                Spacer(Modifier.height(10.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("مؤشرات إضافية", fontWeight = FontWeight.Bold)
                        info?.nutritionGrade?.let {
                            Spacer(Modifier.height(5.dp))
                            Text("التقييم الغذائي: ${it.uppercase()}")
                        }
                        info?.novaGroup?.let {
                            Text("تصنيف المعالجة NOVA: $it")
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(17.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = "الملاحظات الغذائية تعتمد على البيانات المتاحة للمنتج وليست تشخيصًا أو نصيحة طبية. إذا لم تتوفر معلومة موثوقة نعرضها كغير متوفرة بدل التخمين.",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NotesSection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    notes: List<String>,
    positive: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (positive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(21.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(title, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            notes.forEach { note ->
                Text(
                    text = "• $note",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

private fun manufacturingText(info: ProductInfo?): String {
    val parts = listOfNotNull(
        info?.manufacturingCountry?.takeIf { it.isNotBlank() }
            ?.let { "بلد الصنع: $it" },
        info?.manufacturingPlaces?.takeIf { it.isNotBlank() }
            ?.let { "مكان التصنيع: $it" }
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
        ?: "بلد التصنيع غير متوفر في المصدر الحالي."
}
