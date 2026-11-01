package btools.router;

import java.util.Map;

import btools.expressions.BExpressionContextNode;
import btools.expressions.BExpressionContextWay;

/** Makes BRouter search with {@link CurveonPath}; curveon-moto.brf names it, as a ---model line. */
final class CurveonModel extends OsmPathModel {
  @Override
  public OsmPrePath createPrePath() {
    return null;
  }

  @Override
  public OsmPath createPath() {
    return new CurveonPath();
  }

  @Override
  public void init(BExpressionContextWay expctxWay, BExpressionContextNode expctxNode, Map<String, String> keyValues) {
  }
}
