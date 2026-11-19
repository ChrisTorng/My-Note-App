package tw.idv.christorng.mynoteapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fully native screens; no WebView, Compose, AndroidX or third-party libraries. */
public final class MainActivity extends Activity {
    private static final int NAVY=Color.rgb(28,84,140), PAPER=Color.rgb(247,249,252), MUTED=Color.rgb(100,116,132);
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final AtomicBoolean syncing=new AtomicBoolean(false);
    private LocalDb db;
    private ApiClient api;
    private SyncEngine sync;
    private boolean quickRequested, archived, homeShowing, editorReadOnly;
    private String category="inbox", search="", lastSavedText="";
    private LocalDb.Note activeNote;
    private long sessionEditTime;
    private EditText noteInput;
    private Spinner categorySpinner;
    private TextView statusView;
    private ListView listView;
    private List<LocalDb.Note> list=new ArrayList<>();
    private BaseAdapter listAdapter;
    private Runnable saveJob;
    private boolean foreground;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!foreground) return;
            requestSync();
            ui.postDelayed(this,60000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if(Build.VERSION.SDK_INT>=35) getWindow().setNavigationBarContrastEnforced(false);
        db=new LocalDb(this);
        api=new ApiClient(this);
        sync=new SyncEngine(api,db);
        quickRequested=isQuickIntent(getIntent());
        if(api.hasSession()) openInitial(); else showLogin();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if(isQuickIntent(intent)) {
            finishEditing();
            quickRequested=true;
            if(api.hasSession()) showEditor(null);
            else showLogin();
        }
    }
    private static boolean isQuickIntent(Intent intent) {
        return intent.getBooleanExtra("quick",false)
                || "tw.idv.christorng.mynoteapp.QUICK_ADD".equals(intent.getAction());
    }
    @Override protected void onResume() {
        super.onResume();foreground=true;
        ui.removeCallbacks(poll);
        if(api.hasSession()) ui.post(poll);
    }
    @Override protected void onPause() {
        foreground=false;ui.removeCallbacks(poll);
        if(!editorReadOnly) saveEditor(true);
        super.onPause();
    }
    @Override protected void onDestroy() {
        ui.removeCallbacks(poll);
        if(saveJob!=null)ui.removeCallbacks(saveJob);
        io.shutdown();
        db.close();
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if(!homeShowing && activeNote!=null) { finishEditing();showHome();return; }
        super.onBackPressed();
    }

    private int dp(float value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v; }
    private LinearLayout row() { LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v; }
    private LinearLayout.LayoutParams fill() {return new LinearLayout.LayoutParams(-1,-1);}
    private LinearLayout.LayoutParams wrap() {return new LinearLayout.LayoutParams(-2,-2);}
    private LinearLayout.LayoutParams weight() {return new LinearLayout.LayoutParams(0,-2,1);}
    private LinearLayout.LayoutParams weightHeight() {return new LinearLayout.LayoutParams(-1,0,1);}
    private void pad(View v,int horizontal,int vertical) {v.setPadding(dp(horizontal),dp(vertical),dp(horizontal),dp(vertical));}
    private Button button(String text,Runnable onClick) {
        Button b=new Button(this);b.setText(text);b.setTextSize(12);
        b.setAllCaps(false);b.setMinHeight(dp(42));
        if(onClick!=null)b.setOnClickListener(v->onClick.run());
        return b;
    }
    private TextView text(String value,int size,boolean bold) {
        TextView t=new TextView(this);t.setText(value);t.setTextColor(Color.rgb(30,43,60));t.setTextSize(size);
        if(bold)t.setTypeface(null,Typeface.BOLD);return t;
    }
    private LinearLayout root() {
        LinearLayout r=column();r.setBackgroundColor(PAPER);r.setFitsSystemWindows(true);pad(r,12,8);
        setContentView(r);return r;
    }
    private void banner(LinearLayout r,String title,Button action) {
        LinearLayout line=row();
        TextView heading=text(title,20,true);heading.setTextColor(NAVY);
        line.addView(heading,weight());
        if(action!=null)line.addView(action,wrap());
        r.addView(line,new LinearLayout.LayoutParams(-1,-2));
    }
    private void message(String s) {
        if(statusView!=null)statusView.setText(s);
    }
    private void toast(String s) {Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}

    private void openInitial() {
        if(quickRequested)showEditor(null);else showHome();
        requestSync();
    }
    private void showLogin() {
        homeShowing=false;activeNote=null;noteInput=null;
        LinearLayout r=root();
        banner(r,"My Note App",null);
        TextView help=text("登入既有 MyNote 帳號。筆記將繼續儲存在原本的 Cloudflare 後端。",14,false);
        pad(help,0,18);r.addView(help);
        TextView nameLabel=text("裝置名稱",13,true);r.addView(nameLabel);
        EditText name=new EditText(this);name.setSingleLine(true);
        name.setText(api.deviceName());r.addView(name,new LinearLayout.LayoutParams(-1,-2));
        TextView codeLabel=text("存取碼",13,true);pad(codeLabel,0,12);r.addView(codeLabel);
        EditText code=new EditText(this);code.setSingleLine(true);
        code.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        r.addView(code,new LinearLayout.LayoutParams(-1,-2));
        Button go=button("登入",()->{
            String access=code.getText().toString(), deviceName=name.getText().toString().trim();
            if(access.isEmpty()) {toast("請輸入存取碼");return;}
            io.execute(()->{
                try {
                    api.login(access,deviceName.isEmpty()?"Android":deviceName);
                    runOnUiThread(()->{code.setText("");openInitial();ui.removeCallbacks(poll);ui.post(poll);});
                }catch(Exception e) {runOnUiThread(()->toast("登入失敗："+e.getMessage()));}
            });
        });
        r.addView(go);
        statusView=text("登入工作階段會加密保存在 Android Keystore。",12,false);
        r.addView(statusView);
    }

    private void showHome() {
        homeShowing=true;activeNote=null;noteInput=null;statusView=null;
        LinearLayout r=root();
        Button add=button("＋ 新增",()->showEditor(null));
        banner(r,"My Note App",add);
        LinearLayout tabs=row();
        Button current=button("Current",()->{archived=false;showHome();});
        Button archive=button("Archive",()->{archived=true;showHome();});
        current.setEnabled(archived);archive.setEnabled(!archived);
        tabs.addView(current,weight());tabs.addView(archive,weight());
        Button more=button("⋮",()->{});more.setOnClickListener(v->showMenu(more));
        tabs.addView(more,wrap());
        r.addView(tabs);
        EditText filter=new EditText(this);filter.setSingleLine(true);filter.setTextSize(14);
        filter.setHint("搜尋筆記內容、裝置、分類");
        filter.setText(search);filter.setSelection(filter.length());
        r.addView(filter,new LinearLayout.LayoutParams(-1,-2));
        HorizontalScrollView strip=new HorizontalScrollView(this);strip.setHorizontalScrollBarEnabled(false);
        LinearLayout nav=row();nav.setPadding(0,dp(2),0,dp(3));
        List<LocalDb.Category> cats=db.categories();
        for(LocalDb.Category c:cats) {
            Button cat=button(c.name,()->{category=c.id;showHome();});
            cat.setEnabled(!category.equals(c.id));nav.addView(cat,wrap());
        }
        Button create=button("＋",this::addCategory);nav.addView(create,wrap());
        strip.addView(nav);r.addView(strip,new LinearLayout.LayoutParams(-1,-2));
        statusView=text("本機筆記已載入",11,false);statusView.setTextColor(MUTED);pad(statusView,2,4);r.addView(statusView);
        listView=new ListView(this);listView.setDividerHeight(dp(1));listView.setCacheColorHint(Color.TRANSPARENT);
        listAdapter=new BaseAdapter() {
            @Override public int getCount(){return list.size();}
            @Override public Object getItem(int position){return list.get(position);}
            @Override public long getItemId(int position){return position;}
            @Override public View getView(int position,View convertView,ViewGroup parent){
                LocalDb.Note n=list.get(position);
                LinearLayout cell;
                TextView preview,info;
                if(convertView instanceof LinearLayout) {
                    cell=(LinearLayout)convertView;
                    preview=(TextView)cell.getChildAt(0);info=(TextView)cell.getChildAt(1);
                } else {
                    cell=column();pad(cell,9,8);cell.setBackgroundColor(Color.WHITE);
                    preview=text("",15,false);preview.setMaxLines(2);preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    info=text("",11,false);info.setTextColor(MUTED);pad(info,0,4);
                    cell.addView(preview,new LinearLayout.LayoutParams(-1,-2));cell.addView(info,new LinearLayout.LayoutParams(-1,-2));
                }
                preview.setText(n.content);
                info.setText(n.originDeviceName+"  ·  "+timestamp(n.createdAt)
                        +(sameMinute(n.createdAt,n.updatedAt)?"":"  · E "+timestamp(n.updatedAt))
                        +(n.originDeviceId.equals(api.deviceId())?"":"  · 唯讀"));
                return cell;
            }
        };
        listView.setAdapter(listAdapter);
        listView.setOnItemClickListener((parent,v,position,id)->showEditor(list.get(position)));
        listView.setOnItemLongClickListener((parent,v,position,id)->{noteActions(list.get(position));return true;});
        r.addView(listView,weightHeight());
        filter.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){search=s.toString();refreshList();}
            @Override public void afterTextChanged(Editable e){}
        });
        refreshList();
    }
    private void refreshList() {
        if(listAdapter==null||!homeShowing)return;
        list=db.list(archived,category,search);
        listAdapter.notifyDataSetChanged();
        message(list.size()+" 筆  ·  "+(archived?"封存":"目前")+"  ·  "+api.deviceName());
    }
    private static boolean sameMinute(long a,long b){return a/60000==b/60000;}
    private static String timestamp(long epoch) {
        return new SimpleDateFormat("MM/dd (E) HH:mm",Locale.TAIWAN).format(new Date(epoch));
    }

    private void showEditor(LocalDb.Note existing) {
        homeShowing=false;
        sessionEditTime=System.currentTimeMillis();
        if(existing==null) {
            LocalDb.Note n=new LocalDb.Note();
            n.id=UUID.randomUUID().toString();n.categoryId=category;n.content="";
            n.originDeviceId=api.deviceId();n.originDeviceName=api.deviceName();
            n.createdAt=sessionEditTime;n.updatedAt=sessionEditTime;n.revision=0;
            activeNote=n;
        }else activeNote=existing;
        editorReadOnly=!activeNote.originDeviceId.equals(api.deviceId());
        lastSavedText=activeNote.content;
        LinearLayout r=root();
        LinearLayout heading=row();
        Button back=button("‹ 返回",()->{finishEditing();showHome();});heading.addView(back,wrap());
        TextView title=text(existing==null?"新增筆記":(editorReadOnly?"檢視筆記":"編輯筆記"),17,true);
        title.setGravity(Gravity.CENTER_VERTICAL);title.setTextColor(NAVY);heading.addView(title,weight());
        Button copy=button("複製",()->copy(noteInput!=null?noteInput.getText().toString():activeNote.content));heading.addView(copy,wrap());
        r.addView(heading);
        List<LocalDb.Category> cats=db.categories();
        if(!editorReadOnly) {
            categorySpinner=new Spinner(this);
            ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item);
            int selected=0;
            for(int i=0;i<cats.size();i++) {
                adapter.add(cats.get(i).name);
                if(cats.get(i).id.equals(activeNote.categoryId))selected=i;
            }
            categorySpinner.setAdapter(adapter);categorySpinner.setSelection(selected);
            categorySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
                @Override public void onItemSelected(AdapterView<?> p,View view,int position,long id){scheduleSave();}
                @Override public void onNothingSelected(AdapterView<?> p){}
            });
            r.addView(categorySpinner,new LinearLayout.LayoutParams(-1,-2));
        } else {
            TextView readOnlyLabel=text("此筆記由其他裝置建立，本文只能讀取；仍可封存或複製。",12,false);
            pad(readOnlyLabel,1,12);r.addView(readOnlyLabel);
        }
        if(editorReadOnly) {
            ScrollView sc=new ScrollView(this);
            TextView read=text(activeNote.content,17,false);
            read.setAutoLinkMask(Linkify.WEB_URLS);read.setLinksClickable(true);
            pad(read,7,12);sc.addView(read);r.addView(sc,weightHeight());
        } else {
            noteInput=new EditText(this);
            noteInput.setGravity(Gravity.TOP|Gravity.START);
            noteInput.setTextSize(17);noteInput.setBackgroundColor(Color.WHITE);pad(noteInput,10,12);
            noteInput.setHint("立即輸入筆記…");
            noteInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            noteInput.setText(activeNote.content);noteInput.setSelection(noteInput.length());
            r.addView(noteInput,weightHeight());
            noteInput.addTextChangedListener(new TextWatcher(){
                @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
                @Override public void onTextChanged(CharSequence s,int start,int before,int count){scheduleSave();}
                @Override public void afterTextChanged(Editable e){}
            });
            if(existing==null) {
                noteInput.requestFocus();
                ui.postDelayed(()->{
                    if(noteInput!=null && noteInput.hasFocus()) {
                        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(noteInput,InputMethodManager.SHOW_IMPLICIT);
                    }
                },140);
            }
        }
        LinearLayout bottom=row();
        if(!editorReadOnly)bottom.addView(button("儲存",()->{saveEditor(false);requestSync();toast("已儲存本機");}),wrap());
        if(existing!=null) {
            bottom.addView(button(existing.archivedAt==null?"封存":"還原",()->{
                saveEditor(false);
                db.archive(activeNote.id,activeNote.archivedAt==null);
                requestSync();finishEditing();showHome();
            }),wrap());
            if(!editorReadOnly)bottom.addView(button("刪除",()->confirmDelete(activeNote)),wrap());
        }
        r.addView(bottom,new LinearLayout.LayoutParams(-1,-2));
        statusView=text("離線可用 · 自動儲存",11,false);statusView.setTextColor(MUTED);r.addView(statusView);
    }

    private void scheduleSave() {
        if(saveJob!=null)ui.removeCallbacks(saveJob);
        saveJob=()->saveEditor(false);
        ui.postDelayed(saveJob,550);
    }
    private void saveEditor(boolean closing) {
        if(activeNote==null || editorReadOnly || noteInput==null)return;
        if(saveJob!=null){ui.removeCallbacks(saveJob);saveJob=null;}
        String content=noteInput.getText().toString();
        String selectedCategory=activeNote.categoryId;
        if(categorySpinner!=null) {
            List<LocalDb.Category> cats=db.categories();
            int i=categorySpinner.getSelectedItemPosition();
            if(i>=0&&i<cats.size())selectedCategory=cats.get(i).id;
        }
        if(content.equals(lastSavedText) && selectedCategory.equals(activeNote.categoryId))return;
        if(content.trim().isEmpty()) {
            if(closing && activeNote.revision>0){db.remove(activeNote.id);requestSync();}
            return;
        }
        activeNote.categoryId=selectedCategory;
        activeNote.content=content;
        activeNote.revision=Math.max(0,activeNote.revision)+1;
        activeNote.updatedAt=activeNote.revision==1?activeNote.createdAt:sessionEditTime;
        db.save(activeNote);
        lastSavedText=content;
        message("已儲存本機 · 待同步");
        requestSync();
    }
    private void finishEditing() {
        saveEditor(true);
        activeNote=null;noteInput=null;categorySpinner=null;
        editorReadOnly=false;
    }
    private void noteActions(LocalDb.Note note) {
        boolean owned=note.originDeviceId.equals(api.deviceId());
        String[] actions=owned
                ?new String[]{"複製全部","開啟筆記",note.archivedAt==null?"封存":"還原","刪除"}
                :new String[]{"複製全部","開啟筆記",note.archivedAt==null?"封存":"還原"};
        new AlertDialog.Builder(this).setTitle("筆記操作").setItems(actions,(dialog,which)->{
            if(which==0)copy(note.content);
            if(which==1)showEditor(note);
            if(which==2) {db.archive(note.id,note.archivedAt==null);requestSync();refreshList();}
            if(which==3)confirmDelete(note);
        }).show();
    }
    private void confirmDelete(LocalDb.Note note) {
        if(!note.originDeviceId.equals(api.deviceId())){toast("只能在建立端刪除筆記");return;}
        new AlertDialog.Builder(this).setTitle("刪除筆記？")
                .setMessage("刪除將同步至所有裝置，無法復原。")
                .setNegativeButton("取消",null)
                .setPositiveButton("刪除",(dialog,which)->{
                    finishEditing();db.remove(note.id);requestSync();showHome();
                }).show();
    }
    private void copy(String contents) {
        ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("MyNote",contents));toast("已複製");
    }
    private void showMenu(View anchor) {
        PopupMenu popup=new PopupMenu(this,anchor);
        Menu menu=popup.getMenu();
        menu.add("新增分類");menu.add("重新命名目前分類");menu.add("刪除目前分類");
        menu.add("重新整理 / 同步");menu.add("建立桌面新增捷徑");menu.add("更改裝置名稱");menu.add("重新登入");
        popup.setOnMenuItemClickListener(item->{
            String label=item.getTitle().toString();
            if(label.equals("新增分類"))addCategory();
            else if(label.startsWith("重新命名"))renameCategory();
            else if(label.startsWith("刪除目前"))deleteCategory();
            else if(label.startsWith("重新整理"))requestSync();
            else if(label.startsWith("建立桌面"))pinShortcut();
            else if(label.startsWith("更改裝置"))renameDevice();
            else if(label.equals("重新登入")){
                // Preserve offline notes and device identity; revoke only the current session.
                api.clearSession();showLogin();
            }
            return true;
        });
        popup.show();
    }
    private interface TextAction {void run(String text) throws Exception;}
    private void ask(String title,String initial,TextAction action) {
        EditText value=new EditText(this);value.setSingleLine(true);
        value.setText(initial);value.setSelection(value.length());
        new AlertDialog.Builder(this).setTitle(title).setView(value)
                .setNegativeButton("取消",null)
                .setPositiveButton("確定",(dialog,which)->{
                    String s=value.getText().toString().trim();if(s.isEmpty())return;
                    try {action.run(s);}catch(Exception e){toast(e.getMessage());}
                }).show();
    }
    private void doCategory(String method,JSONObject body) {
        io.execute(()->{
            try {
                api.request("/category",method,body);
                sync.run();
                runOnUiThread(this::showHome);
            }catch(Exception e){runOnUiThread(()->toast("分類異動失敗："+e.getMessage()));}
        });
    }
    private void addCategory(){ask("新增分類","",name->{
        doCategory("POST",new JSONObject().put("id",UUID.randomUUID().toString()).put("name",name));
    });}
    private void renameCategory(){
        LocalDb.Category current=null;
        for(LocalDb.Category c:db.categories())if(c.id.equals(category))current=c;
        if(current==null)return;
        String id=current.id;
        ask("重新命名分類",current.name,name->doCategory("PATCH",new JSONObject().put("id",id).put("name",name)));
    }
    private void deleteCategory(){
        if("inbox".equals(category)){toast("Inbox 不可刪除");return;}
        String id=category;
        new AlertDialog.Builder(this).setTitle("刪除分類？")
                .setMessage("分類內筆記會移回 Inbox。")
                .setNegativeButton("取消",null)
                .setPositiveButton("刪除",(dialog,which)->{
                    category="inbox";
                    try{doCategory("DELETE",new JSONObject().put("id",id));}
                    catch(Exception e){toast(e.getMessage());}
                }).show();
    }
    private void renameDevice() {
        ask("裝置名稱",api.deviceName(),name->{
            io.execute(()->{
                try {
                    api.request("/device","PATCH",new JSONObject().put("name",name));
                    api.changeName(name);requestSync();runOnUiThread(this::showHome);
                }catch(Exception e){runOnUiThread(()->toast("更名失敗："+e.getMessage()));}
            });
        });
    }
    private void pinShortcut() {
        if(Build.VERSION.SDK_INT<26){toast("需要 Android 8 以上");return;}
        ShortcutManager manager=getSystemService(ShortcutManager.class);
        if(manager==null || !manager.isRequestPinShortcutSupported()) {
            toast("請長按 App 圖示，拖曳「新增筆記」到桌面");return;
        }
        Intent launch=new Intent(this,MainActivity.class).setAction("tw.idv.christorng.mynoteapp.QUICK_ADD")
                .putExtra("quick",true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        ShortcutInfo shortcut=new ShortcutInfo.Builder(this,"pinned_new_note")
                .setShortLabel("新增筆記").setLongLabel("My Note App：立即新增筆記")
                .setIcon(Icon.createWithResource(this,R.drawable.ic_quick))
                .setIntent(launch).build();
        manager.requestPinShortcut(shortcut,null);
    }

    private void requestSync() {
        if(!api.hasSession()||!syncing.compareAndSet(false,true))return;
        io.execute(()->{
            String error=null;
            try {sync.run();}
            catch(ApiClient.ApiException e){
                if(e.code==401)error="登入過期，請重新登入；本機筆記已保留";
                else error="同步失敗（"+e.code+"）："+e.getMessage();
            }catch(Exception e){error="離線或同步失敗，已保留本機資料";}
            finally {syncing.set(false);}
            String result=error;
            runOnUiThread(()->{
                if(homeShowing)refreshList();
                if(result!=null)message(result);
                else if(!homeShowing)message("已同步");
            });
        });
    }
}
