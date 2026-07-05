package com.example.depth;

import org.springframework.stereotype.Service;

/**
 * At maxDepth=3, this is the TRAVERSAL_CUTOFF layer.
 * Its callees (processF, processG) should appear as callee signatures
 * but NOT as independent entries in the flattened output.
 */
@Service
public class DepthServiceE {

    private final DepthServiceF serviceF;
    private final DepthServiceG serviceG;

    public DepthServiceE(DepthServiceF serviceF, DepthServiceG serviceG) {
        this.serviceF = serviceF;
        this.serviceG = serviceG;
    }

    public void processE() {
        serviceF.processF();
        serviceG.processG();
    }
}
