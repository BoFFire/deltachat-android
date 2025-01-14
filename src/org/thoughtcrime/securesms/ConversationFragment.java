/*
 * Copyright (C) 2015 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.fragment.app.Fragment;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.OnScrollListener;
import android.text.ClipboardManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcEventCenter;
import com.b44t.messenger.DcMsg;

import org.thoughtcrime.securesms.ConversationAdapter.ItemClickListener;
import org.thoughtcrime.securesms.connect.ApplicationDcContext;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.Debouncer;
import org.thoughtcrime.securesms.util.SaveAttachmentTask;
import org.thoughtcrime.securesms.util.StickyHeaderDecoration;
import org.thoughtcrime.securesms.util.ViewUtil;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.b44t.messenger.DcContact.DC_CONTACT_ID_SELF;
import static org.thoughtcrime.securesms.util.RelayUtil.REQUEST_RELAY;
import static org.thoughtcrime.securesms.util.RelayUtil.setForwardingMessageIds;

@SuppressLint("StaticFieldLeak")
public class ConversationFragment extends Fragment
        implements DcEventCenter.DcEventDelegate
{
    private static final String TAG       = ConversationFragment.class.getSimpleName();
    private static final String KEY_LIMIT = "limit";

    private static final int SCROLL_ANIMATION_THRESHOLD = 50;
    private static final int CODE_ADD_EDIT_CONTACT      = 77;

    private final ActionModeCallback actionModeCallback     = new ActionModeCallback();
    private final ItemClickListener  selectionClickListener = new ConversationFragmentItemClickListener();

    private ConversationFragmentListener listener;

    private Recipient                   recipient;
    private long                        chatId;
    private int                         startingPosition;
    private boolean                     firstLoad;
    private ActionMode                  actionMode;
    private Locale                      locale;
    private RecyclerView                list;
    private RecyclerView.ItemDecoration lastSeenDecoration;
    private StickyHeaderDecoration      dateDecoration;
    private View                        scrollToBottomButton;
    private View                        floatingLocationButton;
    private TextView                    noMessageTextView;
    private ApplicationDcContext        dcContext;

    private Debouncer markseenDebouncer;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        this.locale = (Locale) getArguments().getSerializable(PassphraseRequiredActionBarActivity.LOCALE_EXTRA);
        this.dcContext = DcHelper.getContext(getContext());

        dcContext.eventCenter.addObserver(DcContext.DC_EVENT_INCOMING_MSG, this);
        dcContext.eventCenter.addObserver(DcContext.DC_EVENT_MSGS_CHANGED, this);
        dcContext.eventCenter.addObserver(DcContext.DC_EVENT_MSG_DELIVERED, this);
        dcContext.eventCenter.addObserver(DcContext.DC_EVENT_MSG_FAILED, this);
        dcContext.eventCenter.addObserver(DcContext.DC_EVENT_MSG_READ, this);
        dcContext.eventCenter.addObserver(DcContext.DC_EVENT_CHAT_MODIFIED, this);

        markseenDebouncer = new Debouncer(800);
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN)
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle bundle) {
        final View view = inflater.inflate(R.layout.conversation_fragment, container, false);
        list                   = ViewUtil.findById(view, android.R.id.list);
        scrollToBottomButton   = ViewUtil.findById(view, R.id.scroll_to_bottom_button);
        floatingLocationButton = ViewUtil.findById(view, R.id.floating_location_button);
        noMessageTextView      = ViewUtil.findById(view, R.id.no_messages_text_view);

        scrollToBottomButton.setOnClickListener(v -> scrollToBottom());

        final LinearLayoutManager layoutManager = new LinearLayoutManager(getActivity(), LinearLayoutManager.VERTICAL, true);
        list.setHasFixedSize(false);
        list.setLayoutManager(layoutManager);
        list.setItemAnimator(null);

        // setLayerType() is needed to allow larger items (long texts in our case)
        // with hardware layers, drawing may result in errors as "OpenGLRenderer: Path too large to be rendered into a texture"
        list.setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        return view;
    }

    @Override
    public void onActivityCreated(Bundle bundle) {
        super.onActivityCreated(bundle);
        initializeResources();
        initializeListAdapter();
    }

    private void setNoMessageText() {
        if(chatId == DcChat.DC_CHAT_ID_DEADDROP) {
            if(DcHelper.getInt(getActivity(), "show_emails")!= DcContext.DC_SHOW_EMAILS_ALL) {
                noMessageTextView.setText(R.string.chat_no_contact_requests);
            }
            else {
                noMessageTextView.setText(R.string.chat_no_messages);
            }
        }
        else if(getListAdapter().isGroupChat()){
            if(dcContext.getChat((int) chatId).isUnpromoted()) {
                noMessageTextView.setText(R.string.chat_new_group_hint);
            }
            else {
                noMessageTextView.setText(R.string.chat_no_messages);
            }
        }else{
            String name = getListAdapter().getChatName();
            if(dcContext.getChat((int) chatId).isSelfTalk()) {
                noMessageTextView.setText(R.string.chat_no_messages);
            }
            else {
                String message = getString(R.string.chat_no_messages_hint, name, name);
                noMessageTextView.setText(message);
            }
        }
    }

    @Override
    public void onDestroy() {
        dcContext.eventCenter.removeObservers(this);
        super.onDestroy();
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        this.listener = (ConversationFragmentListener)activity;
    }

    @Override
    public void onResume() {
        super.onResume();

        dcContext.marknoticedChat((int) chatId);
        if (list.getAdapter() != null) {
            list.getAdapter().notifyDataSetChanged();
        }
    }


    @Override
    public void onPause() {
        super.onPause();
        setLastSeen(System.currentTimeMillis());
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        dateDecoration.onConfigurationChanged(newConfig);
    }

    public void onNewIntent() {
        if (actionMode != null) {
            actionMode.finish();
        }

        initializeResources();
        initializeListAdapter();

        if (chatId == -1) {
            reloadList();
            updateLocationButton();
        }
    }

    public void moveToLastSeen() {
        if (list == null || getListAdapter() == null) {
            Log.w(TAG, "Tried to move to last seen position, but we hadn't initialized the view yet.");
            return;
        }

        if (getListAdapter().getLastSeenPosition() < 0) {
            return;
        }
        scrollToLastSeenPosition(getListAdapter().getLastSeenPosition());
    }

    private void initializeResources() {
        this.chatId            = this.getActivity().getIntent().getIntExtra(ConversationActivity.CHAT_ID_EXTRA, -1);
        this.recipient         = Recipient.from(getActivity(), Address.fromChat((int)this.chatId));
        this.startingPosition  = this.getActivity().getIntent().getIntExtra(ConversationActivity.STARTING_POSITION_EXTRA, -1);
        this.firstLoad         = true;

        OnScrollListener scrollListener = new ConversationScrollListener(getActivity());
        list.addOnScrollListener(scrollListener);
    }

    private void initializeListAdapter() {
        if (this.recipient != null && this.chatId != -1) {
            ConversationAdapter adapter = new ConversationAdapter(getActivity(), this.recipient.getChat(), GlideApp.with(this), locale, selectionClickListener, this.recipient);
            list.setAdapter(adapter);
            dateDecoration = new StickyHeaderDecoration(adapter, false, false);
            list.addItemDecoration(dateDecoration);

            reloadList();
            updateLocationButton();
        }
    }

    private void setCorrectMenuVisibility(Menu menu) {
        Set<DcMsg>         messageRecords = getListAdapter().getSelectedItems();

        if (actionMode != null && messageRecords.size() == 0) {
            actionMode.finish();
            return;
        }

        if (messageRecords.size() > 1) {
            menu.findItem(R.id.menu_context_details).setVisible(false);
            menu.findItem(R.id.menu_context_save_attachment).setVisible(false);
        } else {
            DcMsg messageRecord = messageRecords.iterator().next();
            menu.findItem(R.id.menu_context_details).setVisible(true);
            menu.findItem(R.id.menu_context_save_attachment).setVisible(messageRecord.hasFile());
        }
    }

    private ConversationAdapter getListAdapter() {
        return (ConversationAdapter) list.getAdapter();
    }

    private DcMsg getSelectedMessageRecord() {
        Set<DcMsg> messageRecords = getListAdapter().getSelectedItems();

        if (messageRecords.size() == 1) return messageRecords.iterator().next();
        else                            throw new AssertionError();
    }

    public void reload(Recipient recipient, long chatId) {
        this.recipient = recipient;

        if (this.chatId != chatId) {
            this.chatId = chatId;
            initializeListAdapter();
        }
    }

    public void scrollToBottom() {
        if (((LinearLayoutManager) list.getLayoutManager()).findFirstVisibleItemPosition() < SCROLL_ANIMATION_THRESHOLD) {
            list.smoothScrollToPosition(0);
        } else {
            list.scrollToPosition(0);
        }
    }

    public void setLastSeen(long lastSeen) {
        getListAdapter().setLastSeen(lastSeen);
        if (lastSeenDecoration != null) {
            list.removeItemDecoration(lastSeenDecoration);
        }

        if (lastSeen > 0) {
            lastSeenDecoration = new ConversationAdapter.LastSeenHeader(getListAdapter()/*, lastSeen*/);
            list.addItemDecoration(lastSeenDecoration);
        }
    }

    private String getMessageContent(DcMsg msg, DcMsg prev_msg)
    {
        String ret = "";

        if (msg.getFromId() != prev_msg.getFromId()) {
            DcContact contact = dcContext.getContact(msg.getFromId());
            ret += contact.getDisplayName() + ":\n";
        }

        if(msg.getType() == DcMsg.DC_MSG_TEXT) {
            ret += msg.getText();
        }
        else {
            ret += msg.getSummarytext(1000);
        }

        return ret;
    }

    private void handleCopyMessage(final Set<DcMsg> dcMsgsSet) {
        List<DcMsg> dcMsgsList = new LinkedList<>(dcMsgsSet);
        Collections.sort(dcMsgsList, new Comparator<DcMsg>() {
            @Override
            public int compare(DcMsg lhs, DcMsg rhs) {
                if      (lhs.getDateReceived() < rhs.getDateReceived())  return -1;
                else if (lhs.getDateReceived() == rhs.getDateReceived()) return 0;
                else                                                     return 1;
            }
        });

        StringBuilder result = new StringBuilder();

        DcMsg prevMsg = new DcMsg(dcContext, DcMsg.DC_MSG_TEXT);
        for (DcMsg msg : dcMsgsList) {
            if (result.length()>0) {
                result.append("\n\n");
            }
            result.append(getMessageContent(msg, prevMsg));
            prevMsg = msg;
        }

        if (result.length()>0) {
            try {
                ClipboardManager clipboard = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setText(result.toString());
                Toast.makeText(getActivity(), getActivity().getResources().getString(R.string.copied_to_clipboard), Toast.LENGTH_LONG).show();
            }
            catch(Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void handleDeleteMessages(final Set<DcMsg> messageRecords) {
        int                 messagesCount = messageRecords.size();

        new AlertDialog.Builder(getActivity())
                .setMessage(getActivity().getResources().getQuantityString(R.plurals.ask_delete_messages, messagesCount, messagesCount))
                .setCancelable(true)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    int[] ids = DcMsg.msgSetToIds(messageRecords);
                    dcContext.deleteMsgs(ids);
                    actionMode.finish();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void handleDisplayDetails(DcMsg dcMsg) {
        String info_str = dcContext.getMsgInfo(dcMsg.getId());
        new AlertDialog.Builder(getActivity())
                .setMessage(info_str)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void handleForwardMessage(final Set<DcMsg> messageRecords) {
        Intent composeIntent = new Intent(getActivity(), ConversationListActivity.class);
        int[] msgIds = DcMsg.msgSetToIds(messageRecords);
        setForwardingMessageIds(composeIntent, msgIds);
        startActivityForResult(composeIntent, REQUEST_RELAY);
        getActivity().overridePendingTransition(R.anim.slide_from_right, R.anim.fade_scale_out);
    }

    private void handleResendMessage(final DcMsg message) {
        // TODO
    /*
    final Context context = getActivity().getApplicationContext();
    new AsyncTask<MessageRecord, Void, Void>() {
      @Override
      protected Void doInBackground(MessageRecord... messageRecords) {
        MessageSender.resend(context, messageRecords[0]);
        return null;
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, message);
    */
    }

    private void handleReplyMessage(final DcMsg message) {
        listener.handleReplyMessage(message);
    }

    private void handleSaveAttachment(final DcMsg message) {
        SaveAttachmentTask.showWarningDialog(getContext(), (dialogInterface, i) -> {
            Permissions.with(this)
                    .request(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
                    .ifNecessary()
                    .withPermanentDenialDialog(getString(R.string.perm_explain_access_to_storage_denied))
                    .onAnyDenied(() -> Toast.makeText(getContext(), R.string.perm_explain_access_to_storage_denied, Toast.LENGTH_LONG).show())
                    .onAllGranted(() -> {
                        SaveAttachmentTask saveTask = new SaveAttachmentTask(getContext());
                        SaveAttachmentTask.Attachment attachment = new SaveAttachmentTask.Attachment(
                                Uri.fromFile(message.getFileAsFile()), message.getFilemime(), message.getDateReceived(), message.getFilename());
                        saveTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, attachment);
                        actionMode.finish();
                    })
                    .execute();
        });
    }

    private void reloadList() {
        ConversationAdapter adapter = getListAdapter();
        if (adapter == null) {
            return;
        }

        int oldCount = 0;
        int oldIndex = 0;
        int pixelOffset = 0;
        if (!firstLoad) {
            oldCount = adapter.getItemCount();
            oldIndex = ((LinearLayoutManager) list.getLayoutManager()).findFirstCompletelyVisibleItemPosition();
            View firstView = list.getLayoutManager().findViewByPosition(oldIndex);
            pixelOffset = (firstView == null) ? 0 : list.getBottom() - firstView.getBottom() - list.getPaddingBottom();
        }

        int[] msgs = DcHelper.getContext(getContext()).getChatMsgs((int) chatId, 0, 0);
        adapter.changeData(msgs);
        int lastSeenPosition = adapter.getLastSeenPosition();

        if (firstLoad) {
            if (startingPosition >= 0) {
                scrollToStartingPosition(startingPosition);
            } else {
                scrollToLastSeenPosition(lastSeenPosition);
            }
            firstLoad = false;
        } else if(oldIndex  > 0) {
            int newIndex = oldIndex + msgs.length - oldCount;

            if (newIndex < 0)                 { newIndex = 0; pixelOffset = 0; }
            else if (newIndex >= msgs.length) { newIndex = msgs.length - 1; pixelOffset = 0; }

            ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(newIndex, pixelOffset);
        }

        if(!adapter.isActive()){
            setNoMessageText();
            noMessageTextView.setVisibility(View.VISIBLE);
        }
        else{
            noMessageTextView.setVisibility(View.GONE);
        }
    }

    private void updateLocationButton() {
        floatingLocationButton.setVisibility(dcContext.isSendingLocationsToChat((int) chatId)? View.VISIBLE : View.GONE);
    }

    private void scrollToStartingPosition(final int startingPosition) {
        list.post(() -> {
            list.getLayoutManager().scrollToPosition(startingPosition);
            getListAdapter().pulseHighlightItem(startingPosition);
        });
    }

    private void scrollToLastSeenPosition(final int lastSeenPosition) {
        if (lastSeenPosition > 0) {
            list.post(() -> ((LinearLayoutManager)list.getLayoutManager()).scrollToPositionWithOffset(lastSeenPosition, list.getHeight()));
        }
    }

    public interface ConversationFragmentListener {
        void setChatId(int threadId);
        void handleReplyMessage(DcMsg messageRecord);
    }

    private class ConversationScrollListener extends OnScrollListener {

        private final Animation              scrollButtonInAnimation;
        private final Animation              scrollButtonOutAnimation;

        private boolean wasAtBottom           = true;
        private boolean wasAtZoomScrollHeight = false;
        //private long    lastPositionId        = -1;

        ConversationScrollListener(@NonNull Context context) {
            this.scrollButtonInAnimation  = AnimationUtils.loadAnimation(context, R.anim.fade_scale_in);
            this.scrollButtonOutAnimation = AnimationUtils.loadAnimation(context, R.anim.fade_scale_out);

            this.scrollButtonInAnimation.setDuration(100);
            this.scrollButtonOutAnimation.setDuration(50);
        }

        @Override
        public void onScrolled(final RecyclerView rv, final int dx, final int dy) {
            boolean currentlyAtBottom           = isAtBottom();
            boolean currentlyAtZoomScrollHeight = isAtZoomScrollHeight();
//            int     positionId                  = getHeaderPositionId();

            if (currentlyAtBottom && !wasAtBottom) {
                ViewUtil.animateOut(scrollToBottomButton, scrollButtonOutAnimation, View.INVISIBLE);
            }

            if (currentlyAtZoomScrollHeight && !wasAtZoomScrollHeight) {
                ViewUtil.animateIn(scrollToBottomButton, scrollButtonInAnimation);
            }

//      if (positionId != lastPositionId) {
//        bindScrollHeader(conversationDateHeader, positionId);
//      }

            wasAtBottom           = currentlyAtBottom;
            wasAtZoomScrollHeight = currentlyAtZoomScrollHeight;
//            lastPositionId        = positionId;

            markseenDebouncer.publish(() -> manageMessageSeenState());
        }

        @Override
        public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
//      if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
//        conversationDateHeader.show();
//      } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
//        conversationDateHeader.hide();
//      }
        }

        private boolean isAtBottom() {
            if (list.getChildCount() == 0) return true;

            View    bottomView       = list.getChildAt(0);
            int     firstVisibleItem = ((LinearLayoutManager) list.getLayoutManager()).findFirstVisibleItemPosition();
            boolean isAtBottom       = (firstVisibleItem == 0);

            return isAtBottom && bottomView.getBottom() <= list.getHeight();
        }

        private boolean isAtZoomScrollHeight() {
            return ((LinearLayoutManager) list.getLayoutManager()).findFirstCompletelyVisibleItemPosition() > 4;
        }

 //       private int getHeaderPositionId() {
 //           return ((LinearLayoutManager)list.getLayoutManager()).findLastVisibleItemPosition();
 //       }

 //       private void bindScrollHeader(HeaderViewHolder headerViewHolder, int positionId) {
 //           if (((ConversationAdapter)list.getAdapter()).getHeaderId(positionId) != -1) {
 //               ((ConversationAdapter) list.getAdapter()).onBindHeaderViewHolder(headerViewHolder, positionId);
 //           }
 //       }
    }

    private void manageMessageSeenState() {

        LinearLayoutManager layoutManager = (LinearLayoutManager)list.getLayoutManager();

        int firstPos = layoutManager.findFirstVisibleItemPosition();
        int lastPos = layoutManager.findLastVisibleItemPosition();
        if(firstPos == RecyclerView.NO_POSITION || lastPos == RecyclerView.NO_POSITION) {
            return;
        }

        int[] ids = new int[lastPos - firstPos + 1];
        int index = 0;
        for(int pos = firstPos; pos <= lastPos; pos++) {
            DcMsg message = ((ConversationAdapter) list.getAdapter()).getMsg(pos);
            if (message.getFromId() != DC_CONTACT_ID_SELF && !message.isSeen()) {
                ids[index] = message.getId();
                index++;
            }
        }
        dcContext.markseenMsgs(ids);
    }


    void querySetupCode(final DcMsg dcMsg, String[] preload)
    {
        if( !dcMsg.isSetupMessage()) {
            return;
        }

        View gl = View.inflate(getActivity(), R.layout.setup_code_grid, null);
        final EditText[] editTexts = {
                gl.findViewById(R.id.setupCode0),  gl.findViewById(R.id.setupCode1),  gl.findViewById(R.id.setupCode2),
                gl.findViewById(R.id.setupCode3),  gl.findViewById(R.id.setupCode4),  gl.findViewById(R.id.setupCode5),
                gl.findViewById(R.id.setupCode6),  gl.findViewById(R.id.setupCode7),  gl.findViewById(R.id.setupCode8)
        };
        AlertDialog.Builder builder1 = new AlertDialog.Builder(getActivity());
        builder1.setView(gl);
        editTexts[0].setText(dcMsg.getSetupCodeBegin());
        editTexts[0].setSelection(editTexts[0].getText().length());

        for( int i = 0; i < 9; i++ ) {
            if( preload != null && i < preload.length ) {
                editTexts[i].setText(preload[i]);
                editTexts[i].setSelection(editTexts[i].getText().length());
            }
            editTexts[i].addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if( s.length()==4 ) {
                        for ( int i = 0; i < 8; i++ ) {
                            if( editTexts[i].hasFocus() && editTexts[i+1].getText().length()<4 ) {
                                editTexts[i+1].requestFocus();
                                break;
                            }
                        }
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });
        }

        builder1.setTitle(getActivity().getString(R.string.autocrypt_continue_transfer_title));
        builder1.setMessage(getActivity().getString(R.string.autocrypt_continue_transfer_please_enter_code));
        builder1.setNegativeButton(android.R.string.cancel, null);
        builder1.setCancelable(false); // prevent the dialog from being dismissed accidentally (when the dialog is closed, the setup code is gone forever and the user has to create a new setup message)
        builder1.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            String setup_code = "";
            final String[] preload1 = new String[9];
            for ( int i = 0; i < 9; i++ ) {
                preload1[i] = editTexts[i].getText().toString();
                setup_code += preload1[i];
            }
            boolean success = dcContext.continueKeyTransfer(dcMsg.getId(), setup_code);

            AlertDialog.Builder builder2 = new AlertDialog.Builder(getActivity());
            builder2.setTitle(getActivity().getString(R.string.autocrypt_continue_transfer_title));
            builder2.setMessage(getActivity().getString(success? R.string.autocrypt_continue_transfer_succeeded : R.string.autocrypt_bad_setup_code));
            if( success ) {
                builder2.setPositiveButton(android.R.string.ok, null);
            }
            else {
                builder2.setNegativeButton(android.R.string.cancel, null);
                builder2.setPositiveButton(R.string.autocrypt_continue_transfer_retry, (dialog1, which1) -> querySetupCode(dcMsg, preload1));
            }
            builder2.show();
        });
        builder1.show();
    }

    private class ConversationFragmentItemClickListener implements ItemClickListener {

        @Override
        public void onItemClick(DcMsg messageRecord) {
            if (actionMode != null) {
                ((ConversationAdapter) list.getAdapter()).toggleSelection(messageRecord);
                list.getAdapter().notifyDataSetChanged();

                if (getListAdapter().getSelectedItems().size() == 0) {
                    actionMode.finish();
                } else {
                    setCorrectMenuVisibility(actionMode.getMenu());
                    actionMode.setTitle(String.valueOf(getListAdapter().getSelectedItems().size()));
                }
            }
            else if(messageRecord.isSetupMessage()) {
                querySetupCode(messageRecord,null);
            }
        }

        @Override
        public void onItemLongClick(DcMsg messageRecord) {
            if (actionMode == null) {
                ((ConversationAdapter) list.getAdapter()).toggleSelection(messageRecord);
                list.getAdapter().notifyDataSetChanged();

                actionMode = ((AppCompatActivity)getActivity()).startSupportActionMode(actionModeCallback);
            }
        }

        @Override
        public void onMessageSharedContactClicked(@NonNull List<Recipient> choices) {
            if (getContext() == null) return;

//      ContactUtil.selectRecipientThroughDialog(getContext(), choices, locale, recipient -> {
//        CommunicationActions.startConversation(getContext(), recipient, null);
//      });
        }

        @Override
        public void onInviteSharedContactClicked(@NonNull List<Recipient> choices) {
            if (getContext() == null) return;
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == CODE_ADD_EDIT_CONTACT && getContext() != null) {
//      ApplicationContext.getInstance(getContext().getApplicationContext())
//                        .getJobManager()
//                        .add(new DirectoryRefreshJob(getContext().getApplicationContext(), false));
        }
    }

    private class ActionModeCallback implements ActionMode.Callback {

        private int statusBarColor;

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.conversation_context, menu);

            mode.setTitle("1");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Window window = getActivity().getWindow();
                statusBarColor = window.getStatusBarColor();
                window.setStatusBarColor(getResources().getColor(R.color.action_mode_status_bar));
            }

            setCorrectMenuVisibility(menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            ((ConversationAdapter)list.getAdapter()).clearSelection();
            list.getAdapter().notifyDataSetChanged();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                getActivity().getWindow().setStatusBarColor(statusBarColor);
            }

            actionMode = null;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            switch(item.getItemId()) {
                case R.id.menu_context_copy:
                    handleCopyMessage(getListAdapter().getSelectedItems());
                    actionMode.finish();
                    return true;
                case R.id.menu_context_delete_message:
                    handleDeleteMessages(getListAdapter().getSelectedItems());
                    return true;
                case R.id.menu_context_details:
                    handleDisplayDetails(getSelectedMessageRecord());
                    actionMode.finish();
                    return true;
                case R.id.menu_context_forward:
                    handleForwardMessage(getListAdapter().getSelectedItems());
                    actionMode.finish();
                    return true;
                case R.id.menu_context_resend:
                    handleResendMessage(getSelectedMessageRecord());
                    actionMode.finish();
                    return true;
                case R.id.menu_context_save_attachment:
                    handleSaveAttachment(getSelectedMessageRecord());
                    return true;
                case R.id.menu_context_reply:
                    handleReplyMessage(getSelectedMessageRecord());
                    actionMode.finish();
                    return true;
            }

            return false;
        }
    }

    @Override
    public void handleEvent(int eventId, Object data1, Object data2) {
        if (eventId == DcContext.DC_EVENT_CHAT_MODIFIED) {
            updateLocationButton();
        }

        // removing the "new message" marker on incoming messages may be a bit unexpected,
        // esp. when a series of message is coming in and after the first, the screen is turned on,
        // the "new message" marker will flash for a short moment and disappear.
        /*if (eventId == DcContext.DC_EVENT_INCOMING_MSG && isResumed()) {
            setLastSeen(-1);
        }*/

        reloadList();
    }
}
