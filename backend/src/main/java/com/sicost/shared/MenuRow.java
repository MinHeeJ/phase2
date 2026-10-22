package com.sicost.shared;

public record MenuRow(Long menuId, Long parentId, String name, String screenId, String icon, boolean mobile) {}
