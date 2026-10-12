package com.gimble.bus_stop.security.util;

/**
 * One spelling per phone number, so "71 234 567", "+267 71234567" and "26771234567" can't become three accounts.
 * Botswana numbers (8 digits) get the +267 country code; other numbers keep theirs.
 */
public final class PhoneNumbers {

    private PhoneNumbers() {
    }

    public static String normalize(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("[\\s\\-()]", "");
        if (digits.isEmpty()) {
            return null;
        }
        if (digits.startsWith("00")) {
            digits = "+" + digits.substring(2);
        }
        if (digits.matches("\\d{8}")) {
            return "+267" + digits;
        }
        if (digits.matches("267\\d{8}")) {
            return "+" + digits;
        }
        return digits;
    }
}
