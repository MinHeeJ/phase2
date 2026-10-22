package com.sicost.shared;

import java.util.List;

public record MenuNode(Long menuId, String name, String screenId, String icon, boolean mobile, List<MenuNode> children) {}
