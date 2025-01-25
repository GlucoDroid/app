package tk.glucodata.settings;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getbutton;
import static tk.glucodata.util.getcheckbox;
import static tk.glucodata.util.getlabel;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;

import tk.glucodata.Applic;
import tk.glucodata.GlucoseCurve;
import tk.glucodata.Layout;
import tk.glucodata.Log;
import tk.glucodata.MainActivity;
import tk.glucodata.Natives;
import tk.glucodata.R;

class LogConfig {
   final private static String LOG_ID="LogConfig" ;


private static void saveRequest(MainActivity context,String filename,int request) {
    if(doLog&&!isWearable) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, filename);
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        try {
            context.startActivityForResult(intent, request);
            } catch(Throwable th) {
                Log.stack(LOG_ID,"ACTION_CREATE_DOCUMENT",th);
              }
         }
    }
private static void savefile(MainActivity context) {
    if(doLog&&!isWearable) {
        saveRequest(context,"trace.log", MainActivity.REQUEST_SAVE_LOG);
           }
    }
private static void savelogcat(MainActivity context) {
    if(doLog&&!isWearable) {
        saveRequest(context,"logcat.txt", MainActivity.REQUEST_SAVE_LOGCAT);
           }
    }

static void make(MainActivity act,View parent) {
    if(doLog&&!isWearable) {
       parent.setVisibility(GONE);
       final   int subpad=(int)(tk.glucodata.GlucoseCurve.metrics.density*7.0);
        var trace=getlabel(act,"trace.log");
        trace.setPadding(subpad,0,0,0);
        var sizelabel=getlabel(act,R.string.filesize);
        sizelabel.setPadding(0,0,subpad,0);
        var size=getlabel(act,Long.toString(Natives.getLogfilesize()));
        var delete=getbutton(act,R.string.delete);
        var save=getbutton(act,R.string.save);
       save.setOnClickListener(v-> savefile(act));
        var log=getcheckbox(act,R.string.logging, Natives.islogging());
        log.setOnCheckedChangeListener( (buttonView,  isChecked) -> Natives.dolog(isChecked));
        log.setPadding(0,0,subpad,0);

        var logcat=getlabel(act,"logcat");
        logcat.setPadding(subpad,0,0,0);
        var logcaton=getcheckbox(act,R.string.logging, Natives.islogcat());
        logcaton.setOnCheckedChangeListener( (buttonView,  isChecked) -> Natives.dologcat(isChecked));
        logcaton.setPadding(0,0,subpad,0);
        var sizelabel2=getlabel(act,R.string.filesize);
//        sizelabel2.setPadding(subpad,0,subpad,0);
        var logcatsize=getlabel(act,Long.toString(Natives.getLogcatfilesize()));
        var deletelogcat=getbutton(act,R.string.delete);
        var savelogcat=getbutton(act,R.string.save);
       savelogcat.setOnClickListener(v->savelogcat(act));
       // var email=getbutton(act,"E-Mail");
        var close=getbutton(act,R.string.closename);
        var help=getbutton(act, R.string.helpname);
         var width= GlucoseCurve.getwidth();
         var height=GlucoseCurve.getheight();
        var layout = new Layout(act, (l, w, h) -> {
             l.setX((width-w)*.5f);
             l.setY((height-h)*.33f);
            int[] ret={w,h};
            return ret;
            },new View[]{trace,delete,save},new View[] {log,sizelabel,size},
            new View[]{logcat,deletelogcat,savelogcat},new View[] {logcaton,sizelabel2,logcatsize},
            new View[]{help,close});
     //   layout.setBackgroundColor(Applic.backgroundcolor);
      layout.setBackgroundResource(R.drawable.dialogbackground);
       final   int pad=(int)(tk.glucodata.GlucoseCurve.metrics.density*9.0);
       layout.setPadding(pad,pad,pad,pad);
        act.addContentView(layout, new ViewGroup.LayoutParams(WRAP_CONTENT,WRAP_CONTENT));
        help.setOnClickListener(v-> {
            tk.glucodata.help.help(R.string.loghelp,act);
            });
        delete.setOnClickListener(v-> {
            Natives.zeroLog();
            MainActivity.poponback();
            removeContentView(layout) ;
            make(act,parent);
        });
        deletelogcat.setOnClickListener(v-> {
            Natives.zeroLogcat();
            MainActivity.poponback();
            removeContentView(layout) ;
            make(act,parent);
        });
        MainActivity.setonback(() -> {
            removeContentView(layout) ;
            parent.setVisibility(VISIBLE);
            });

       close.setOnClickListener(v-> MainActivity.doonback());
        }
   }

};
