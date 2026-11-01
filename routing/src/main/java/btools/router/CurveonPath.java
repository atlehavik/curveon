/**
 * BRouter's StdPath (1.7.10, MIT, by ab; final, so copied) with one addition, the road rules in
 * {@link #turnRule}: BRouter only knows the turns the map forbids with a turn restriction, and prices
 * every other turn by its angle alone, so a U-turn from one carriageway onto the other cost it 50 m.
 */
package btools.router;

final class CurveonPath extends OsmPath {
  /** At least this sharp (degrees) at a junction is turning back on yourself, not turning off. */
  private static final double REVERSAL_DEG = 135.;
  /** Turned this sharply off a one-way main road onto a one-way road: cutting across a channelled junction. */
  private static final double CUT_ACROSS_DEG = 60.;
  private static final int CUT_ACROSS_COST = 300;
  private static final int REVERSAL_COST = 1000;

  // In the profile: priorityclassifier of primary and of unclassified; classifiermask's bits.
  private static final int MAJOR_ROAD = 26;
  private static final int PUBLIC_ROAD = 20;
  private static final int ONEWAY = 2;
  private static final int ROUNDABOUT = 4;

  /** The classifiermask of the way this path came along. */
  private int wayMask;

  /**
   * The elevation-hysteresis-buffer (0-10 m)
   */
  private int ehbd; // in micrometer
  private int ehbu; // in micrometer

  private float totalTime;  // travel time (seconds)
  private float totalEnergy; // total route energy (Joule)
  private float elevation_buffer; // just another elevation buffer (for travel time)

  private int uphillcostdiv;
  private int downhillcostdiv;

  // Gravitational constant, g
  private static final double GRAVITY = 9.81;  // in meters per second^(-2)

  @Override
  public void init(OsmPath orig) {
    CurveonPath origin = (CurveonPath) orig;
    this.wayMask = origin.wayMask;
    this.ehbd = origin.ehbd;
    this.ehbu = origin.ehbu;
    this.totalTime = origin.totalTime;
    this.totalEnergy = origin.totalEnergy;
    this.elevation_buffer = origin.elevation_buffer;
  }

  @Override
  protected void resetState() {
    ehbd = 0;
    ehbu = 0;
    totalTime = 0.f;
    totalEnergy = 0.f;
    uphillcostdiv = 0;
    downhillcostdiv = 0;
    elevation_buffer = 0.f;
  }

  @Override
  protected double processWaySection(RoutingContext rc, double distance, double delta_h, double elevation, double angle, double cosangle, boolean isStartpoint, int nsection, int lastpriorityclassifier) {
    // calculate the costfactor inputs
    float turncostbase = rc.expctxWay.getTurncost();
    float uphillcutoff = rc.expctxWay.getUphillcutoff() * 10000;
    float downhillcutoff = rc.expctxWay.getDownhillcutoff() * 10000;
    float uphillmaxslope = rc.expctxWay.getUphillmaxslope() * 10000;
    float downhillmaxslope = rc.expctxWay.getDownhillmaxslope() * 10000;
    float cfup = rc.expctxWay.getUphillCostfactor();
    float cfdown = rc.expctxWay.getDownhillCostfactor();
    float cf = rc.expctxWay.getCostfactor();
    cfup = cfup == 0.f ? cf : cfup;
    cfdown = cfdown == 0.f ? cf : cfdown;

    downhillcostdiv = (int) rc.expctxWay.getDownhillcost();
    if (downhillcostdiv > 0) {
      downhillcostdiv = 1000000 / downhillcostdiv;
    }

    int downhillmaxslopecostdiv = (int) rc.expctxWay.getDownhillmaxslopecost();
    if (downhillmaxslopecostdiv > 0) {
      downhillmaxslopecostdiv = 1000000 / downhillmaxslopecostdiv;
    } else {
      // if not given, use legacy behavior
      downhillmaxslopecostdiv = downhillcostdiv;
    }

    uphillcostdiv = (int) rc.expctxWay.getUphillcost();
    if (uphillcostdiv > 0) {
      uphillcostdiv = 1000000 / uphillcostdiv;
    }

    int uphillmaxslopecostdiv = (int) rc.expctxWay.getUphillmaxslopecost();
    if (uphillmaxslopecostdiv > 0) {
      uphillmaxslopecostdiv = 1000000 / uphillmaxslopecostdiv;
    } else {
      // if not given, use legacy behavior
      uphillmaxslopecostdiv = uphillcostdiv;
    }

    int dist = (int) distance; // legacy arithmetics needs int

    int mask = (int) rc.expctxWay.getClassifierMask();
    int rule = isStartpoint || nsection != 0 ? 0 : turnRule(Math.abs(angle), wayMask, mask, lastpriorityclassifier, (int) rc.expctxWay.getPriorityClassifier());
    wayMask = mask;
    if (rule < 0) return -1.;

    // penalty for turning angle
    int turncost = rule + (int) ((1. - cosangle) * turncostbase + 0.2); // e.g. turncost=90 -> 90 degree = 90m penalty

    int newPrio = (int) rc.expctxWay.getPriorityClassifier();
    int oldPrio = lastpriorityclassifier;

    if (rc.bikeMode) {
      //   If the turn is LEFT and coming from "primary|secondary" to a lower priority highway
      //   AND estimated_crossing_class is defined on the node, than penalty!!!

      if (rc.consider_crossing && oldPrio > 0 && nsection == 0  && angle < 0 && oldPrio >= rc.crossing_Prio_H && newPrio <= rc.crossing_Prio_L){
        int class_index = 0;
        if (sourceNode.nodeDescription != null) {
          boolean nodeAccessGranted = rc.expctxWay.getNodeAccessGranted() != 0.;
          String node_tags = rc.expctxNode.getKeyValueDescription(nodeAccessGranted, sourceNode.nodeDescription);
          class_index = node_tags.indexOf("estimated_crossing_class=");
          if (class_index > -1) {
            String crossing_class  = node_tags.substring(class_index + 25, class_index + 26);
            int additional_turn_cost = 0;
            if (crossing_class.equals("1")) {additional_turn_cost = rc.cost_ToLeft_from_H_class1; }
            if (crossing_class.equals("2")) {additional_turn_cost = rc.cost_ToLeft_from_H_class2; }
            if (crossing_class.equals("3")) {additional_turn_cost = rc.cost_ToLeft_from_H_class3; }
            if (crossing_class.equals("4")) {additional_turn_cost = rc.cost_ToLeft_from_H_class4; }
            if (crossing_class.equals("5")) {additional_turn_cost = rc.cost_ToLeft_from_H_class5; }
            if (crossing_class.equals("6")) {additional_turn_cost = rc.cost_ToLeft_from_H_class6; }
            turncost += additional_turn_cost;
          }
        }
      }

      // for left-hand traffic
      // If the turn is RIGHT and coming from "primary|secondary" to a lower priority HW AND estimated_crossing_class is defined on the node, than penalty!!!

      if (rc.consider_crossing && oldPrio > 0 && nsection == 0  && angle > 0 && oldPrio >= rc.crossing_Prio_H && newPrio <= rc.crossing_Prio_L) {
        int class_index = 0;
        if (sourceNode.nodeDescription != null) {
          boolean nodeAccessGranted = rc.expctxWay.getNodeAccessGranted() != 0.;
          String node_tags = rc.expctxNode.getKeyValueDescription(nodeAccessGranted, sourceNode.nodeDescription);
          class_index = node_tags.indexOf("estimated_crossing_class=");
          if (class_index > -1) {
            String crossing_class  = node_tags.substring(class_index + 25, class_index + 26);
            int additional_turn_cost = 0;
            if (crossing_class.equals("1")) {additional_turn_cost = rc.cost_ToRight_from_H_class1; }
            if (crossing_class.equals("2")) {additional_turn_cost = rc.cost_ToRight_from_H_class2; }
            if (crossing_class.equals("3")) {additional_turn_cost = rc.cost_ToRight_from_H_class3; }
            if (crossing_class.equals("4")) {additional_turn_cost = rc.cost_ToRight_from_H_class4; }
            if (crossing_class.equals("5")) {additional_turn_cost = rc.cost_ToRight_from_H_class5; }
            if (crossing_class.equals("6")) {additional_turn_cost = rc.cost_ToRight_from_H_class6; }
            turncost += additional_turn_cost;
          }
        }
      }
    }

    if (message != null) {
      message.linkturncost += turncost;
      message.turnangle = (float) angle;
    }

    double sectionCost = turncost;

    // *** penalty for elevation
    // only the part of the descend that does not fit into the elevation-hysteresis-buffers
    // leads to an immediate penalty

    int delta_h_micros = (int) (1000000. * delta_h);
    ehbd += -delta_h_micros - dist * downhillcutoff;
    ehbu += delta_h_micros - dist * uphillcutoff;

    float downweight = 0.f;
    if (ehbd > rc.elevationpenaltybuffer) {
      downweight = 1.f;

      int excess = ehbd - rc.elevationpenaltybuffer;
      int reduce = dist * rc.elevationbufferreduce;
      if (reduce > excess) {
        downweight = ((float) excess) / reduce;
        reduce = excess;
      }
      excess = ehbd - rc.elevationmaxbuffer;
      if (reduce < excess) {
        reduce = excess;
      }
      ehbd -= reduce;
      float elevationCost = 0.f;
      if (downhillcostdiv > 0) {
        elevationCost += Math.min(reduce, dist * downhillmaxslope) / downhillcostdiv;
      }
      if (downhillmaxslopecostdiv > 0) {
        elevationCost += Math.max(0, reduce - dist * downhillmaxslope) / downhillmaxslopecostdiv;
      }
      if (elevationCost > 0) {
        sectionCost += elevationCost;
        if (message != null) {
          message.linkelevationcost += elevationCost;
        }
      }
    } else if (ehbd < 0) {
      ehbd = 0;
    }

    float upweight = 0.f;
    if (ehbu > rc.elevationpenaltybuffer) {
      upweight = 1.f;

      int excess = ehbu - rc.elevationpenaltybuffer;
      int reduce = dist * rc.elevationbufferreduce;
      if (reduce > excess) {
        upweight = ((float) excess) / reduce;
        reduce = excess;
      }
      excess = ehbu - rc.elevationmaxbuffer;
      if (reduce < excess) {
        reduce = excess;
      }
      ehbu -= reduce;
      float elevationCost = 0.f;
      if (uphillcostdiv > 0) {
        elevationCost += Math.min(reduce, dist * uphillmaxslope) / uphillcostdiv;
      }
      if (uphillmaxslopecostdiv > 0) {
        elevationCost += Math.max(0, reduce - dist * uphillmaxslope) / uphillmaxslopecostdiv;
      }
      if (elevationCost > 0) {
        sectionCost += elevationCost;
        if (message != null) {
          message.linkelevationcost += elevationCost;
        }
      }
    } else if (ehbu < 0) {
      ehbu = 0;
    }

    // get the effective costfactor (slope dependent)
    float costfactor = cfup * upweight + cf * (1.f - upweight - downweight) + cfdown * downweight;

    if (message != null) {
      message.costfactor = costfactor;
    }

    sectionCost += dist * costfactor + 0.5f;

    return sectionCost;
  }

  /**
   * What a turn at a junction costs on top of its angle, or -1 where it may not be ridden at all:
   * onto a one-way road, turning back on yourself. That is the other carriageway, or a ramp that
   * leaves the road the other way round: from a one-way road, or across the oncoming lane of a main
   * road. Turning back onto a two-way road can be the only way to a place, so that only costs.
   */
  private static int turnRule(double angle, int fromMask, int toMask, int fromPrio, int toPrio) {
    if (((fromMask | toMask) & ROUNDABOUT) != 0) return 0;
    boolean fromOneway = (fromMask & ONEWAY) != 0;
    boolean toOneway = (toMask & ONEWAY) != 0;
    boolean fromMajor = fromPrio >= MAJOR_ROAD;
    if (angle >= REVERSAL_DEG) {
      if (toOneway && toPrio >= PUBLIC_ROAD && (fromMajor || fromOneway && fromPrio >= PUBLIC_ROAD)) return -1;
      return fromMajor ? REVERSAL_COST : 0;
    }
    return angle >= CUT_ACROSS_DEG && fromMajor && fromOneway && toOneway ? CUT_ACROSS_COST : 0;
  }

  @Override
  protected double processTargetNode(RoutingContext rc) {
    // finally add node-costs for target node
    if (targetNode.nodeDescription != null) {
      boolean nodeAccessGranted = rc.expctxWay.getNodeAccessGranted() != 0.;
      rc.expctxNode.evaluate(nodeAccessGranted, targetNode.nodeDescription);
      float initialcost = rc.expctxNode.getInitialcost();
      if (initialcost >= 1000000.) {
        return -1.;
      }
      if (message != null) {
        message.linknodecost += (int) initialcost;
        message.nodeKeyValues = rc.expctxNode.getKeyValueDescription(nodeAccessGranted, targetNode.nodeDescription);
      }
      return initialcost;
    }
    return 0.;
  }

  @Override
  public int elevationCorrection() {
    return (downhillcostdiv > 0 ? ehbd / downhillcostdiv : 0)
      + (uphillcostdiv > 0 ? ehbu / uphillcostdiv : 0);
  }

  @Override
  public boolean definitlyWorseThan(OsmPath path) {
    CurveonPath p = (CurveonPath) path;

    int c = p.cost;
    if (p.downhillcostdiv > 0) {
      int delta = p.ehbd / p.downhillcostdiv - (downhillcostdiv > 0 ? ehbd / downhillcostdiv : 0);
      if (delta > 0) c += delta;
    }
    if (p.uphillcostdiv > 0) {
      int delta = p.ehbu / p.uphillcostdiv - (uphillcostdiv > 0 ? ehbu / uphillcostdiv : 0);
      if (delta > 0) c += delta;
    }

    return cost > c;
  }

  private double calcIncline(double dist) {
    double min_delta = 3.;
    double shift = 0.;
    if (elevation_buffer > min_delta) {
      shift = -min_delta;
    } else if (elevation_buffer < -min_delta) {
      shift = min_delta;
    }
    double decayFactor = Math.exp(-dist / 100.);
    float new_elevation_buffer = (float) ((elevation_buffer + shift) * decayFactor - shift);
    double incline = (elevation_buffer - new_elevation_buffer) / dist;
    elevation_buffer = new_elevation_buffer;
    return incline;
  }

  @Override
  protected void computeKinematic(RoutingContext rc, double dist, double delta_h, boolean detailMode) {
    if (!detailMode) {
      return;
    }

    // compute incline
    elevation_buffer += delta_h;
    double incline = calcIncline(dist);

    double maxSpeed = rc.maxSpeed;
    double speedLimit = rc.expctxWay.getMaxspeed() / 3.6f;
    if (speedLimit > 0) {
      maxSpeed = Math.min(maxSpeed, speedLimit);
    }

    double speed = maxSpeed; // Travel speed
    double f_roll = rc.totalMass * GRAVITY * (rc.defaultC_r + incline);
    if (rc.footMode) {
      // Use Tobler's hiking function for walking sections
      speed = rc.maxSpeed * Math.exp(-3.5 * Math.abs(incline + 0.05));
    } else if (rc.bikeMode) {
      speed = solveCubic(rc.S_C_x, f_roll, rc.bikerPower);
      speed = Math.min(speed, maxSpeed);
    }
    float dt = (float) (dist / speed);
    totalTime += dt;
    // Calc energy assuming biking (no good model yet for hiking)
    // (Count only positive, negative would mean breaking to enforce maxspeed)
    double energy = dist * (rc.S_C_x * speed * speed + f_roll);
    if (energy > 0.) {
      totalEnergy += energy;
    }
  }

  private static double solveCubic(double a, double c, double d) {
    // Solves a * v^3 + c * v = d with a Newton method
    // to get the speed v for the section.

    double v = 8.;
    boolean findingStartvalue = true;
    for (int i = 0; i < 10; i++) {
      double y = (a * v * v + c) * v - d;
      if (y < .1) {
        if (findingStartvalue) {
          v *= 2.;
          continue;
        }
        break;
      }
      findingStartvalue = false;
      double y_prime = 3 * a * v * v + c;
      v -= y / y_prime;
    }
    return v;
  }

  @Override
  public double getTotalTime() {
    return totalTime;
  }

  @Override
  public double getTotalEnergy() {
    return totalEnergy;
  }
}
