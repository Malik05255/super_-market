import re
import unittest

import refresh_v2


class EmbeddedDiscoveryTests(unittest.TestCase):
    def setUp(self):
        self.config = {
            "base_url": "https://shop.example.sa/",
            "product_url_regex": r"/products/",
        }
        self.pattern = re.compile(self.config["product_url_regex"], re.I)

    def test_embedded_relative_product_url_is_discovered(self):
        html = '<script>window.__STATE__={"url":"\\/products\\/pepsi-330ml"}</script>'
        urls = refresh_v2._embedded_product_urls(
            html,
            "https://shop.example.sa/en/",
            self.config,
            self.pattern,
        )
        self.assertEqual(urls, ["https://shop.example.sa/products/pepsi-330ml"])

    def test_external_host_is_rejected(self):
        html = '<script>{"url":"https://evil.example/products/fake"}</script>'
        urls = refresh_v2._embedded_product_urls(
            html,
            "https://shop.example.sa/en/",
            self.config,
            self.pattern,
        )
        self.assertEqual(urls, [])

    def test_non_product_path_is_rejected(self):
        html = '<script>{"url":"/category/soft-drinks"}</script>'
        urls = refresh_v2._embedded_product_urls(
            html,
            "https://shop.example.sa/en/",
            self.config,
            self.pattern,
        )
        self.assertEqual(urls, [])


if __name__ == "__main__":
    unittest.main()
