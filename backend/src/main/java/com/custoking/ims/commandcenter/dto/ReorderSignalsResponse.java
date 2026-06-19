package com.custoking.ims.commandcenter.dto;

import java.util.List;

public record ReorderSignalsResponse(int alertCount, List<ReorderSignalItem> items) {}
