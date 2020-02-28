package org.ekstep.graph.model.node;

import java.util.Map;

import org.ekstep.graph.common.mgr.BaseGraphManager;
import org.ekstep.graph.dac.enums.SystemNodeTypes;

public class RootNode extends AbstractNode {

    public RootNode(BaseGraphManager manager, String graphId, String nodeId, Map<String, Object> metadata) {
        super(manager, graphId, nodeId, metadata);
    }

    @Override
    public String getSystemNodeType() {
        return SystemNodeTypes.ROOT_NODE.name();
    }

    @Override
    public String getFunctionalObjectType() {
        return null;
    }

}
