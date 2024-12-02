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

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getlabel;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;
import androidx.wear.widget.CurvedTextView;

class Specific {
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
};
