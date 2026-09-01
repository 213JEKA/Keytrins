package com.keytrins.kucoingrid;

import android.content.*;
import java.text.SimpleDateFormat;
import java.util.*;

final class RiskState {
    private final android.content.SharedPreferences p; private final String pre;
    RiskState(Context c,String symbol){p=c.getSharedPreferences("kag2_state",Context.MODE_PRIVATE);pre=symbol+"_";}
    private String today(){return new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());}
    void rollDay(double equity){String d=today(); if(!d.equals(p.getString(pre+"day",""))){p.edit().putString(pre+"day",d).putFloat(pre+"dayEquity",(float)equity).putString(pre+"haltDay","").apply();}}
    double dayStartEquity(){return p.getFloat(pre+"dayEquity",0f);}
    boolean dayHalted(){return today().equals(p.getString(pre+"haltDay",""));}
    void haltDay(){p.edit().putString(pre+"haltDay",today()).apply();}
    long cooldownUntil(){return p.getLong(pre+"cooldown",0);}
    void cooldown(long until){p.edit().putLong(pre+"cooldown",until).apply();}
    double peak(){return p.getFloat(pre+"peak",0f);}
    double protectedPnl(){return p.getFloat(pre+"protected",0f);}
    void campaign(double peak,double prot){p.edit().putFloat(pre+"peak",(float)peak).putFloat(pre+"protected",(float)prot).apply();}
    void resetCampaign(){p.edit().putFloat(pre+"peak",0f).putFloat(pre+"protected",0f).apply();}
}
