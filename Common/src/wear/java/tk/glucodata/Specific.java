/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */
/*                                                                                   */
/*      Sun Apr 16 20:59:10 CEST 2023                                                 */


package tk.glucodata;

import static android.text.Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE;
import static android.text.Html.fromHtml;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.Applic.backgroundcolor;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getbutton;
import static tk.glucodata.util.getlabel;

import android.annotation.SuppressLint;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;
import androidx.wear.widget.CurvedTextView;
import androidx.wear.widget.WearableLinearLayoutManager;
import androidx.wear.widget.WearableRecyclerView;

public class Specific {
static void start(Object context) { }

static    void splash(AppCompatActivity act) {
       SplashScreen.installSplashScreen(act);
      }
@SuppressLint("StaticFieldLeak")
static ViewGroup layout=null;
@SuppressLint("StaticFieldLeak")
static TextView text=null;

static boolean settext(String str) {
   var t=text;
   if(t!=null) {
       t.setText(str);
       return true;
    }
   return false;
   }
static void rmlayout() {
   var lay=layout;
   if(lay!=null) {
      text=null;
      layout=null;
      removeContentView(lay); 
      }
   }
static void initScreen(MainActivity act) {
	LayoutInflater flater= LayoutInflater.from(act);
	ViewGroup layout = (ViewGroup) flater.inflate(R.layout.startview ,null, false);
	text=layout.findViewById(R.id.text2);
//	layout.setBackgroundColor(Applic.backgroundcolor);
   Specific.layout=layout;
	act.addContentView(layout, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
//   Specific.layout=layout;
//   Specific.text=text;
   }
/*
static class TextViewHolder extends WearableRecyclerView.ViewHolder {
    public TextViewHolder(View view) {
        super(view);

    }

}
static public class TextViewAdapter extends WearableRecyclerView.Adapter<TextViewHolder> {
   TextViewAdapter() {
	   }

    @NonNull
	@Override
    public TextViewHolder  onCreateViewHolder(ViewGroup parent, int viewType) {
    	var view=new TextView( parent.getContext());
	 view.setTransformationMethod(null); //	  view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
	 view.setLayoutParams(new ViewGroup.LayoutParams(  WRAP_CONTENT,  WRAP_CONTENT));
	  view.setGravity(Gravity.CENTER);
        return new TextViewHolder(view);

    }

	@Override
	public void onBindViewHolder(final TextViewHolder holder, int pos) {
		TextView text=(TextView)holder.itemView;
        text.setText(fromHtml(Applic.app.getString(R.string.staticnum),TO_HTML_PARAGRAPH_LINES_CONSECUTIVE));

		}
        @Override
        public int getItemCount() {
		 return 1;
        }

}
static void   blockedNum(MainActivity  act) {
	    var recycle = new WearableRecyclerView(act);
           var lin=new WearableLinearLayoutManager(act);
           recycle.setCircularScrollingGestureEnabled(true);
//            recycle.setLayoutParams(new ViewGroup.LayoutParams(  (int)(width*.9),ViewGroup.LayoutParams.MATCH_PARENT));
            recycle.setLayoutManager(lin);
               recycle.setAdapter(new TextViewAdapter());
	    recycle.setBackgroundColor(backgroundcolor);
           act.addContentView(recycle,new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));
		}    static boolean useclose=true;
static void   blockedNum(MainActivity  act) {
      var text=new TextView(act);
        text.setText(fromHtml(Applic.app.getString(R.string.staticnum),TO_HTML_PARAGRAPH_LINES_CONSECUTIVE));
        text.setMovementMethod(new ScrollingMovementMethod());
       View view=null;
       if(useclose) {
          var close=getbutton(act,R.string.closename);
           close.setOnClickListener(v->{
               MainActivity.doonback();
                  });
            var layout=new Layout(act, (l,w,h)-> { final int[] ret={w,h}; return ret; },new View[]{text},new View[]{close});
            view=layout;
            }
         else
            view=text;
        final var view2=view;
	    view2.setBackgroundColor(backgroundcolor);
	   MainActivity.setonback(()-> {
            removeContentView(view2);
            });
        act.addContentView(view2,new ViewGroup.LayoutParams((int)(GlucoseCurve.getwidth()*0.7),MATCH_PARENT));
		} 
      */
static void   blockedNum(MainActivity  act) {
    var width=GlucoseCurve.getwidth();
    help.basehelp(Applic.app.getString(R.string.staticnum),act,xzy->{ }, (l,w,h)-> {
         var height=GlucoseCurve.getheight();
//			if(height>h) l.setY((height-h)/2);
         if(width>w)
                 l.setX(((width-w)*.55f));
         return new int[] {w,h};
         }, new ViewGroup.MarginLayoutParams((int)(width*0.8), WRAP_CONTENT));
    }

static public boolean useclose=false;
static public void setclose(boolean val) {
   useclose=val;
   }
};
