"""T5/T6 -- app-side CoAP -> Airlink error mapping (documented-rule mirror)."""

from __future__ import annotations

import unittest

from harness.mock_stack.coap import (
    ExtendedError,
    RESPONSE_CONTINUE,
    RESPONSE_FORBIDDEN,
    RESPONSE_SERVICE_UNAVAILABLE,
    RESPONSE_UNAUTHORIZED,
    code_byte,
)
from harness.mock_stack.mapping import (
    RF_ERR_KEY_EXPIRED,
    RF_ERR_STREAM_SYSTEM_NOT_READY,
    app_error_code,
    int_code,
)


class TestIntCode(unittest.TestCase):
    def test_int_code_is_class_times_100_plus_detail(self):
        self.assertEqual(int_code((4, 1)), 401)
        self.assertEqual(int_code((5, 3)), 503)
        self.assertEqual(int_code((2, 4)), 204)

    def test_wire_code_byte(self):
        self.assertEqual(code_byte((4, 1)), 0x81)
        self.assertEqual(code_byte((2, 4)), 0x44)
        self.assertEqual(code_byte(RESPONSE_CONTINUE), 0x5F)


class TestGetMapping(unittest.TestCase):
    def test_unauthorized_masks_any_extended_error(self):
        # 4.01 -> 5378 unconditionally, ignoring the device's ExtendedError.
        self.assertEqual(app_error_code(RESPONSE_UNAUTHORIZED, ExtendedError(0x1234)),
                         RF_ERR_KEY_EXPIRED)
        self.assertEqual(app_error_code(RESPONSE_UNAUTHORIZED, None), RF_ERR_KEY_EXPIRED)

    def test_service_unavailable_maps_to_stream_not_ready(self):
        self.assertEqual(app_error_code(RESPONSE_SERVICE_UNAVAILABLE, ExtendedError(0x1234)),
                         RF_ERR_STREAM_SYSTEM_NOT_READY)

    def test_other_codes_pass_extended_error_through(self):
        self.assertEqual(app_error_code(RESPONSE_FORBIDDEN, ExtendedError(0x1234)), 0x1234)

    def test_other_codes_without_extended_error_yield_zero(self):
        self.assertEqual(app_error_code(RESPONSE_FORBIDDEN, None), 0)
        self.assertEqual(app_error_code((2, 4), None), 0)

    def test_key_expired_mapping_is_not_evidence_of_expiry(self):
        # The mapping is unconditional, so it cannot distinguish a real expiry
        # from any other unauthorized denial.
        self.assertEqual(app_error_code(RESPONSE_UNAUTHORIZED, ExtendedError(0x0AAA)),
                         app_error_code(RESPONSE_UNAUTHORIZED, None))


if __name__ == "__main__":
    unittest.main()
