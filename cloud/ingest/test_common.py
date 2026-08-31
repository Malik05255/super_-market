import unittest
from decimal import Decimal

from common import extract_product, normalize_price, valid_gtin


GTIN_A = "5449000000996"
GTIN_B = "4006381333931"


class CommonExtractionTests(unittest.TestCase):
    def test_gtin_checksum_validation(self):
        self.assertEqual(valid_gtin(GTIN_A), GTIN_A)
        self.assertIsNone(valid_gtin("5449000000995"))
        self.assertIsNone(valid_gtin("1234"))

    def test_product_local_gtin_selects_correct_offer_with_recommendation(self):
        html = f'''
        <html><head>
          <script type="application/ld+json">
          [
            {{"@type":"Product","name":"Main Cola 330 ml","gtin13":"{GTIN_A}",
              "offers":{{"price":"2.50","priceCurrency":"SAR"}}}},
            {{"@type":"Product","name":"Recommended Water","gtin13":"{GTIN_B}",
              "offers":{{"price":"1.00","priceCurrency":"SAR"}}}}
          ]
          </script>
        </head></html>
        '''
        product = extract_product(html, "https://shop.example.sa/products/main", expected_barcode=GTIN_A)
        self.assertIsNotNone(product)
        self.assertEqual(product.barcode, GTIN_A)
        self.assertEqual(product.name_en, "Main Cola 330 ml")
        self.assertEqual(product.price, Decimal("2.50"))

    def test_global_embedded_gtin_does_not_bind_to_first_of_multiple_products(self):
        html = f'''
        <html><head>
          <script type="application/ld+json">
          [
            {{"@type":"Product","name":"Main Cola","offers":{{"price":"2.50","priceCurrency":"SAR"}}}},
            {{"@type":"Product","name":"Recommended Water","offers":{{"price":"1.00","priceCurrency":"SAR"}}}}
          ]
          </script>
          <script>window.__STATE__={{"barcode":"{GTIN_A}"}}</script>
        </head></html>
        '''
        self.assertIsNone(
            extract_product(html, "https://shop.example.sa/products/main", expected_barcode=GTIN_A)
        )

    def test_single_product_can_use_unambiguous_embedded_gtin(self):
        html = f'''
        <html><head>
          <script type="application/ld+json">
            {{"@type":"Product","name":"Main Cola","offers":{{"price":"2.50","priceCurrency":"SAR"}}}}
          </script>
          <script>window.__STATE__={{"barcode":"{GTIN_A}"}}</script>
        </head></html>
        '''
        product = extract_product(html, "https://shop.example.sa/products/main", expected_barcode=GTIN_A)
        self.assertIsNotNone(product)
        self.assertEqual(product.barcode, GTIN_A)
        self.assertEqual(product.price, Decimal("2.50"))

    def test_discovery_rejects_multiple_product_local_gtins(self):
        html = f'''
        <script type="application/ld+json">
        [
          {{"@type":"Product","name":"A","gtin13":"{GTIN_A}","offers":{{"price":"2.50"}}}},
          {{"@type":"Product","name":"B","gtin13":"{GTIN_B}","offers":{{"price":"1.00"}}}}
        ]
        </script>
        '''
        self.assertIsNone(extract_product(html, "https://shop.example.sa/category"))

    def test_price_normalization(self):
        self.assertEqual(normalize_price("SAR 12.50"), Decimal("12.50"))
        self.assertEqual(normalize_price("12٫75 ر.س"), Decimal("12.75"))
        self.assertIsNone(normalize_price("not available"))


if __name__ == "__main__":
    unittest.main()
