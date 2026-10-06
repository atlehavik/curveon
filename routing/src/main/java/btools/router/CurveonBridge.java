package btools.router;

import java.util.ArrayList;
import java.util.List;

/**
 * Lives in BRouter's package to read the package-private turn instruction and way data
 * of a found track, and copies it into plain public objects.
 */
public final class CurveonBridge {

  private CurveonBridge() {}

  public static final class Hint {
    public int indexInTrack;
    /** One of: C TL TSLL TSHL TR TSLR TSHR KL KR TLU TRU TU RNDB RNLB BL EL ER END OFFR */
    public String command;
    public int roundaboutExit;
    public float angle;
  }

  public static final class Point {
    public double lat;
    public double lon;
    public double ele;
    /** Way tags of the segment leading to this point, e.g. "highway=secondary surface=asphalt maxspeed=80". */
    public String wayTags;
  }

  public static List<Point> points(OsmTrack track) {
    List<Point> out = new ArrayList<>(track.nodes.size());
    for (OsmPathElement n : track.nodes) {
      Point p = new Point();
      p.lat = (n.getILat() - 90_000_000) / 1_000_000.0;
      p.lon = (n.getILon() - 180_000_000) / 1_000_000.0;
      p.ele = n.getElev();
      p.wayTags = n.message != null ? n.message.wayKeyValues : null;
      out.add(p);
    }
    return out;
  }

  public static List<Hint> hints(OsmTrack track) {
    List<Hint> out = new ArrayList<>();
    if (track.voiceHints == null || track.voiceHints.list == null) return out;
    for (VoiceHint v : track.voiceHints.list) {
      Hint h = new Hint();
      h.indexInTrack = v.indexInTrack;
      h.command = commandName(v.cmd);
      h.roundaboutExit = v.roundaboutExit;
      h.angle = v.angle;
      out.add(h);
    }
    return out;
  }

  private static String commandName(int cmd) {
    if (cmd == VoiceHint.C) return "C";
    if (cmd == VoiceHint.TL) return "TL";
    if (cmd == VoiceHint.TSLL) return "TSLL";
    if (cmd == VoiceHint.TSHL) return "TSHL";
    if (cmd == VoiceHint.TR) return "TR";
    if (cmd == VoiceHint.TSLR) return "TSLR";
    if (cmd == VoiceHint.TSHR) return "TSHR";
    if (cmd == VoiceHint.KL) return "KL";
    if (cmd == VoiceHint.KR) return "KR";
    if (cmd == VoiceHint.TLU) return "TLU";
    if (cmd == VoiceHint.TRU) return "TRU";
    if (cmd == VoiceHint.TU) return "TU";
    if (cmd == VoiceHint.RNDB) return "RNDB";
    if (cmd == VoiceHint.RNLB) return "RNLB";
    if (cmd == VoiceHint.BL) return "BL";
    if (cmd == VoiceHint.EL) return "EL";
    if (cmd == VoiceHint.ER) return "ER";
    if (cmd == VoiceHint.END) return "END";
    if (cmd == VoiceHint.OFFR) return "OFFR";
    return "C";
  }
}
