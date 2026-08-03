package com.aivle.be.auth.jwt;

public enum AuthRole {
    USER,
    GUEST;

    public String authority() {
        return "ROLE_" + name();
    }
}
