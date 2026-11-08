import java.nio.file.*;
import java.time.*;
import java.util.*;
import com.google.gson.*;
import ru.liturgyguide.model.*;
import ru.liturgyguide.service.*;
public class Probe {
 public static void main(String[] args) throws Exception {
  Path root=Path.of(args[0]); Gson gson=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
  StageMatcher matcher=new StageMatcher(Files.readString(root.resolve("liturgy_stages.json")));
  DailyReadings readings=gson.fromJson(Files.readString(root.resolve("daily_readings.json")),DailyReadings.class);
  List<Object> plans=new ArrayList<>();
  for(String date:List.of("2026-11-08","2026-11-09")) {
   ReadingDay day=readings.getDays().stream().filter(d -> d.getDate().equals(date)).findFirst().orElse(null);
   ServicePlaybackPlan plan=ServicePlaybackPlan.Companion.build(matcher.getAllStages(),LocalDate.parse(date),day);
   Map<String,Object> result=new LinkedHashMap<>(); result.put("serviceDate",date); result.put("fingerprint",plan.getFingerprint());result.put("contentVersion",plan.getContentVersion());
   List<Object> segments=new ArrayList<>(); int index=0;
   for(PlaybackSegment s:plan.getSegments()) {
    Map<String,Object> row=new LinkedHashMap<>();row.put("index",index++);row.put("stageId",s.getStageId());row.put("type",s.getClass().getSimpleName());
    if(s instanceof PlaybackSegment.Text t){row.put("trackId",t.getTrackId());row.put("lineId",t.getLineId());row.put("durationBaseMillis",t.getDurationBaseMillis());PlayerLine line=plan.line(t);row.put("original",line.getOriginal());row.put("russian",line.getRussian());}
    if(s instanceof PlaybackSegment.Wait w)row.put("durationBaseMillis",w.getDurationBaseMillis());
    segments.add(row);
   }
   result.put("segments",segments);plans.add(result);
  }
  Files.writeString(root.resolve("published-plans.json"),gson.toJson(plans));
  for(Object p:plans){Map<?,?> m=(Map<?,?>)p;System.out.println(m.get("serviceDate")+" "+m.get("fingerprint")+" segments="+((List<?>)m.get("segments")).size());}
 }
}
