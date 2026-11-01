package org.diy.liferecorder;

import java.net.URI;

/** Parses configuration without DNS, capability discovery or network access. */
public final class ProviderAddress {
    private ProviderAddress() {}

    public static String validate(String input) {
        String value = input.trim();
        if (value.isEmpty()) return "";
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() < -1 || uri.getPort() > 65535) {
                throw new IllegalArgumentException("请填写 HTTP(S) API 基础地址，不在地址里放密钥、查询参数或片段");
            }
            return uri.toASCIIString();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("API 地址格式不正确");
        }
    }
}
