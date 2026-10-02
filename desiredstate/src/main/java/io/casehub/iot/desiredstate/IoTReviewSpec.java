package io.casehub.iot.desiredstate;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import java.util.Objects;

public record IoTReviewSpec(NodeId faultedNode, String reason) implements NodeSpec {
    public IoTReviewSpec {
        Objects.requireNonNull(faultedNode, "faultedNode");
        Objects.requireNonNull(reason, "reason");
    }

    public NodeType nodeType() { return NodeType.of("iot-review"); }
}
