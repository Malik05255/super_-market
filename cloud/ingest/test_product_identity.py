from decimal import Decimal
import unittest

from common import ExtractedProduct
from product_identity import derive_identity


def product(barcode: str, name: str, brand: str = "Pepsi") -> ExtractedProduct:
    return ExtractedProduct(
        barcode=barcode,
        name_ar=None,
        name_en=name,
        brand=brand,
        image_url=None,
        price=Decimal("2.50"),
        currency="SAR",
        source_url="https://example.test/product",
    )


class ProductIdentityTests(unittest.TestCase):
    def test_same_regular_product_same_identity(self):
        a = derive_identity(product("12345670", "Pepsi Regular Can 330 ml"))
        b = derive_identity(product("12345688", "Pepsi Regular 330ml Can"))
        self.assertIsNotNone(a.identity_key)
        self.assertEqual(a.identity_key, b.identity_key)

    def test_different_size_never_merges(self):
        small = derive_identity(product("12345670", "Pepsi Regular 330 ml"))
        large = derive_identity(product("12345688", "Pepsi Regular 2.25 L"))
        self.assertNotEqual(small.identity_key, large.identity_key)

    def test_zero_never_merges_with_regular(self):
        regular = derive_identity(product("12345670", "Pepsi Regular 330 ml"))
        zero = derive_identity(product("12345688", "Pepsi Zero 330 ml"))
        self.assertNotEqual(regular.identity_key, zero.identity_key)

    def test_diet_never_merges_with_regular(self):
        regular = derive_identity(product("12345670", "Pepsi Regular 330 ml"))
        diet = derive_identity(product("12345688", "Pepsi Diet 330 ml"))
        self.assertNotEqual(regular.identity_key, diet.identity_key)

    def test_pack_count_never_merges_with_single(self):
        single = derive_identity(product("12345670", "Pepsi Regular 330 ml"))
        pack = derive_identity(product("12345688", "Pepsi Regular 6 x 330 ml"))
        self.assertNotEqual(single.identity_key, pack.identity_key)
        self.assertEqual(pack.pack_count, 6)

    def test_missing_size_stays_isolated(self):
        unknown = derive_identity(product("12345670", "Pepsi Regular Can"))
        self.assertIsNone(unknown.identity_key)
        self.assertEqual(unknown.confidence, 0.0)

    def test_liters_normalize_to_milliliters(self):
        one = derive_identity(product("12345670", "Pepsi Regular 1 L"))
        self.assertEqual(one.net_content_value, Decimal("1000.000"))
        self.assertEqual(one.net_content_unit, "ml")

    def test_bonus_mass_is_summed_before_identity(self):
        promo = derive_identity(product("12345670", "Nutella Hazelnut Spread 750 + 75 G", "Nutella"))
        total = derive_identity(product("12345688", "Nutella Hazelnut Spread 825 G", "Nutella"))
        self.assertEqual(promo.net_content_value, Decimal("825.000"))
        self.assertEqual(promo.net_content_unit, "g")
        self.assertEqual(promo.identity_key, total.identity_key)

    def test_bonus_volume_with_units_on_both_terms_is_summed(self):
        promo = derive_identity(product("12345670", "Juice Original 1 L + 250 ml", "Example"))
        total = derive_identity(product("12345688", "Juice Original 1250 ml", "Example"))
        self.assertEqual(promo.net_content_value, Decimal("1250.000"))
        self.assertEqual(promo.net_content_unit, "ml")
        self.assertEqual(promo.identity_key, total.identity_key)


if __name__ == "__main__":
    unittest.main()
