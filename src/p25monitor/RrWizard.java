package p25monitor;

import p25monitor.rr.*;
import p25monitor.rr.RrModel.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * "Set up from RadioReference" (1.6.0): sign in with the user's own RadioReference Premium account, pick a system
 * and site(s), choose talkgroups, done - or refresh a system set up this way. Fetches only while the wizard runs
 * (no background polling). Applying never restarts the radio engine or any feed/upload: talkgroup changes are live;
 * a changed control channel is offered as "Restart" for when the user chooses (feeds stay connected then too).
 */
final class RrWizard extends JDialog {
    static final String[] STEPS = {"Sign in", "System", "Site", "Talkgroups", "Finish"};
    final MainFrame f;
    final RadioSystem refresh;            // null = a new system
    final RrKeys.Found key;
    final CardLayout cards = new CardLayout();
    final JPanel body = new JPanel(cards);
    final JLabel[] stepLabels = new JLabel[STEPS.length];
    final JLabel status = new JLabel(" ");
    final JButton back = MainFrame.button("Back", null, Theme.DIM), next = MainFrame.button("Next", null, Theme.GREEN), cancel = MainFrame.button("Cancel", null, Theme.DIM);
    int step;
    int busyGen;
    SwingWorker<?, ?> worker;
    RrSoap rr;
    RrImport.Fetched fetched;
    int chosenCtid;
    /** The county just shown, so "P25 conventional channels" can read its groups. */
    CountyInfo countyInfo;
    /** Subcategory id (negative sid in the system list) -> the frequencies RadioReference returned. */
    final Map<Integer, List<Freq>> convFreqs = new HashMap<>();
    /** A conventional system built from county frequencies, waiting on the last step. */
    RadioSystem pendingConv;

    // step 1
    final JTextField user = new JTextField(22);
    final JPasswordField pass = new JPasswordField(22);
    final JCheckBox remember = new JCheckBox("Remember me on this computer");
    final JButton forget = MainFrame.button("Forget saved password", null, Theme.DIM);
    // step 2
    final JComboBox<Country> country = new JComboBox<>();
    final JComboBox<State> state = new JComboBox<>();
    final DefaultListModel<Object> countyModel = new DefaultListModel<>();
    final JList<Object> counties = new JList<>(countyModel);
    final DefaultListModel<TrsRef> systemModel = new DefaultListModel<>();
    final JList<TrsRef> systems = new JList<>(systemModel);
    final JTextField zip = new JTextField(6), idField = new JTextField(8);
    final JComboBox<String> idKind = new JComboBox<>(new String[]{"RadioReference system ID", "P25 System ID (hex)"});
    boolean loadingLists;
    static final String STATEWIDE = "Statewide / multi-county systems";
    // step 3
    final SiteModel siteModel = new SiteModel();
    final JTable siteTable = new JTable(siteModel);
    final JLabel siteNote = new JLabel();
    // step 4
    final TgModel tgModel = new TgModel();
    final JTable tgTable = new JTable(tgModel);
    final JLabel tgCount = new JLabel();
    final JLabel tgNote = new JLabel();
    final JCheckBox updateNames = new JCheckBox("Update talkgroup names from RadioReference (untick to keep names you changed yourself)", true);
    // step 5
    final JTextField sysName = new JTextField(30);
    final JCheckBox startNow = new JCheckBox("Start monitoring it now");
    final JEditorPane finishText = new JEditorPane("text/html", "");
    boolean done;

    // ------------------------------------------------------------------ entry points
    /** Opens the wizard, or explains that this build has no RadioReference app key. */
    static void open(MainFrame f, RadioSystem refresh) {
        RrKeys.Found k = RrKeys.find(f.app.home, f.app.profile);
        if (k == null) { unavailable(f); return; }
        RrWizard w = new RrWizard(f, refresh, k);
        w.setVisible(true);
    }

    static void unavailable(MainFrame f) {
        Object[] opts = {"Enter an app key\u2026", "Set up by hand", "Close"};
        int r = JOptionPane.showOptionDialog(f, "<html><body style='width:420px'><b>RadioReference setup isn't available in this build.</b><br><br>"
                + "It needs a RadioReference app key, which is not part of TrunkDeck's source code. Builds that include one can set up a system from "
                + "RadioReference in a few steps (with your own RadioReference Premium account).<br><br>"
                + "If you have a key for TrunkDeck, enter it here or on the Engine page (Advanced view). Otherwise you can enter your system's control "
                + "channels on the Systems page and import a talkgroup CSV on the Talkgroups page.</body></html>",
                "Set up from RadioReference", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, opts, opts[2]);
        if (r == 0) { if (askKey(f)) open(f, null); }
        else if (r == 1) f.show("Systems");
    }

    /** Asks for the app key and saves it in the settings folder; true if one was saved. */
    static boolean askKey(Component parent) {
        MainFrame f = (MainFrame) SwingUtilities.getAncestorOfClass(MainFrame.class, parent);
        if (f == null && parent instanceof MainFrame mf) f = mf;
        JPasswordField k = new JPasswordField(36);
        int r = JOptionPane.showConfirmDialog(parent, new Object[]{"RadioReference app key (it is saved in your TrunkDeck settings folder, readable only by your account):", k},
                "RadioReference app key", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        String v = new String(k.getPassword()).trim();
        if (r != JOptionPane.OK_OPTION || v.isEmpty() || f == null) return false;
        try { RrKeys.save(f.app.profile, v); f.nativePage.updateRrKey(); return true; }
        catch (Exception ex) { JOptionPane.showMessageDialog(parent, "Could not save the key: " + ex.getMessage()); return false; }
    }

    RrWizard(MainFrame f, RadioSystem refresh, RrKeys.Found key) {
        super(f, refresh == null ? "Set up from RadioReference" : "Refresh from RadioReference", ModalityType.APPLICATION_MODAL);
        this.f = f; this.refresh = refresh; this.key = key;
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(Theme.BG);
        root.setBorder(new EmptyBorder(14, 16, 12, 16));
        root.add(header(), BorderLayout.NORTH);
        body.setOpaque(false);
        body.add(signInStep(), "0"); body.add(systemStep(), "1"); body.add(siteStep(), "2"); body.add(talkgroupStep(), "3"); body.add(finishStep(), "4");
        root.add(body, BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);
        setContentPane(root);
        setSize(Layouts.s(940), Layouts.s(640));
        setMinimumSize(new Dimension(760, 520));
        setLocationRelativeTo(f);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() { public void windowClosed(java.awt.event.WindowEvent e) { if (worker != null) worker.cancel(true); } });
        RrLogin l = RrLogin.load(f.app.profile);
        user.setText(l.username); pass.setText(l.password); remember.setSelected(l.remember && !l.password.isEmpty());
        forget.setVisible(l.remember && !l.password.isEmpty());
        go(0);
        SwingUtilities.invokeLater(() -> (user.getText().isBlank() ? user : pass.getPassword().length == 0 ? pass : next).requestFocusInWindow());
    }

    JComponent header() {
        JPanel h = new JPanel(new BorderLayout(0, 8)); h.setOpaque(false);
        JLabel t = MainFrame.h1(refresh == null ? "Set up from RadioReference" : "Refresh \u201c" + refresh.name + "\u201d from RadioReference");
        t.setBorder(new EmptyBorder(0, 2, 0, 2));
        h.add(t, BorderLayout.NORTH);
        JPanel s = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); s.setOpaque(false);
        for (int i = 0; i < STEPS.length; i++) {
            stepLabels[i] = new JLabel((i + 1) + "  " + STEPS[i]);
            stepLabels[i].setFont(Theme.ui(Font.BOLD, 12f));
            stepLabels[i].setBorder(new EmptyBorder(4, 10, 4, 10));
            stepLabels[i].setOpaque(true);
            s.add(stepLabels[i]);
            if (i < STEPS.length - 1) { JLabel a = new JLabel("  \u203a  "); a.setForeground(Theme.LINE); s.add(a); }
        }
        h.add(s, BorderLayout.CENTER);
        return h;
    }

    JComponent footer() {
        JPanel p = new JPanel(new BorderLayout(10, 0)); p.setOpaque(false);
        status.setFont(Theme.ui(Font.PLAIN, 12f));
        p.add(status, BorderLayout.CENTER);
        JPanel b = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); b.setOpaque(false);
        b.add(cancel); b.add(back); b.add(next);
        p.add(b, BorderLayout.EAST);
        back.addActionListener(e -> { if (step > (refresh != null ? 0 : 0)) go(prevStep()); });
        next.addActionListener(e -> onNext());
        cancel.addActionListener(e -> dispose());
        next.putClientProperty("JButton.buttonType", "default");
        getRootPane().setDefaultButton(next);
        return p;
    }

    int prevStep() { return refresh != null && step == 2 ? 0 : step - 1; }

    void go(int s) {
        step = s;
        cards.show(body, String.valueOf(s));
        for (int i = 0; i < STEPS.length; i++) {
            boolean skip = refresh != null && i == 1;
            stepLabels[i].setBackground(i == s ? Theme.PANEL2 : Theme.BG);
            stepLabels[i].setForeground(i == s ? Theme.CYAN : i < s ? Theme.TEXT : skip ? Theme.LINE : Theme.DIM);
            stepLabels[i].setBorder(new CompoundBorder(new LineBorder(i == s ? Theme.CYAN : Theme.BG, 1, true), new EmptyBorder(3, 9, 3, 9)));
        }
        back.setVisible(s > 0 && !done);
        next.setText(s == 4 ? (done ? "Done" : refresh == null ? "Create system" : "Apply update") : "Next");
        cancel.setVisible(!done);
        info(" ");
    }

    void info(String t) { status.setForeground(Theme.DIM); status.setText("<html>" + t + "</html>"); }
    void error(String t) { status.setForeground(Theme.RED); status.setText("<html><body style='width:" + Layouts.s(560) + "px'>" + Util.xml(t) + "</body></html>"); }

    /** Runs a RadioReference request off the UI thread; buttons wait; errors are shown in the status line. */
    <T> void run(String busy, Callable<T> job, Consumer<T> then) {
        int gen = ++busyGen;
        next.setEnabled(false); back.setEnabled(false);
        status.setForeground(Theme.CYAN); status.setText(busy + "\u2026");
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        worker = new SwingWorker<T, Void>() {
            protected T doInBackground() throws Exception { return job.call(); }
            protected void done() {
                if (gen != busyGen || !isDisplayable()) return;
                setCursor(Cursor.getDefaultCursor());
                next.setEnabled(true); back.setEnabled(true);
                if (isCancelled()) return;
                try { T v = get(); info(" "); then.accept(v); }
                catch (java.util.concurrent.ExecutionException ex) {
                    Throwable c = ex.getCause();
                    error(c instanceof RrException || c instanceof IllegalStateException ? c.getMessage() : "Something went wrong: " + c);
                    if (c instanceof RrException re && (re.kind == RrException.Kind.LOGIN || re.kind == RrException.Kind.NOT_PREMIUM) && step != 0) go(0);
                } catch (Exception ex) { error(String.valueOf(ex)); }
            }
        };
        worker.execute();
    }

    void onNext() {
        if (done) { dispose(); return; }
        switch (step) {
            case 0 -> signIn();
            case 1 -> openSystem();
            case 2 -> sitesChosen();
            case 3 -> { prepareFinish(); go(4); }
            case 4 -> apply();
        }
    }

    // ------------------------------------------------------------------ step 1: sign in
    JComponent signInStep() {
        JPanel c = MainFrame.card("Your RadioReference account");
        JPanel g = form();
        GridBagConstraints k = gbc();
        addRow(g, k, "Username", user);
        addRow(g, k, "Password", pass);
        remember.setOpaque(false);
        JPanel rm = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); rm.setOpaque(false); rm.add(remember); rm.add(Box.createHorizontalStrut(12)); rm.add(forget);
        addRow(g, k, "", rm);
        forget.addActionListener(e -> {
            try { RrLogin.forget(f.app.profile); } catch (Exception ignored) {}
            pass.setText(""); remember.setSelected(false); forget.setVisible(false); info("The saved password was removed.");
        });
        addRow(g, k, "", note("Use your own RadioReference account (the one you use on radioreference.com). It needs an active <b>Premium</b> "
                + "subscription - RadioReference only lets Premium members download system data into apps. Your login is sent only to RadioReference, "
                + "and only while this window fetches data."));
        addRow(g, k, "", note("<b>Remember me</b> (off by default) saves the password in your TrunkDeck settings folder, scrambled and readable only by "
                + "your user account. That keeps it out of plain sight, but anyone who can open your account's files could recover it - leave it off on "
                + "a shared computer. Without it, TrunkDeck asks for the password each time you set up or refresh a system."));
        addRow(g, k, "", note("App key: " + key.source() + ". RadioReference data is for your own use; please don't share or republish it."));
        k.weighty = 1; k.gridy++; g.add(Box.createGlue(), k);
        c.add(g, BorderLayout.CENTER);
        return c;
    }

    void signIn() {
        String u = user.getText().trim(), p = new String(pass.getPassword());
        if (u.isEmpty() || p.isEmpty()) { error("Enter your RadioReference username and password."); (u.isEmpty() ? user : pass).requestFocusInWindow(); return; }
        boolean rem = remember.isSelected();
        run("Signing in to RadioReference", () -> {
            RrSoap s = new RrSoap(key.key(), u, p, App.NAME + "/" + App.VERSION).cacheIn(f.app.profile.resolve("rr-cache"));
            UserInfo ui = s.signIn();
            RrLogin l = new RrLogin(); l.username = u; l.password = p; l.remember = rem; l.save(f.app.profile);
            rr = s;
            return ui;
        }, ui -> {
            forget.setVisible(rem);
            String who = "Signed in as " + Util.xml(ui.username()) + (ui.subExpireDate().isBlank() ? "" : " \u00b7 Premium until " + Util.xml(ui.subExpireDate()));
            if (refresh != null) { fetchSystem(refresh.rrSid, 0, who); return; }
            go(1);
            info(who);
            if (country.getItemCount() == 0) loadCountries();
        });
    }

    // ------------------------------------------------------------------ step 2: state / county / system
    JComponent systemStep() {
        JPanel c = MainFrame.card("Find your system");
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); top.setOpaque(false);
        top.add(dim("Country")); top.add(country); top.add(Box.createHorizontalStrut(8));
        top.add(dim("State / province")); top.add(state); top.add(Box.createHorizontalStrut(8));
        top.add(dim("or ZIP code")); top.add(zip);
        JButton find = MainFrame.button("Find", Theme.Glyph.SEARCH, Theme.CYAN); top.add(find);
        find.addActionListener(e -> findZip());
        zip.addActionListener(e -> findZip());
        country.setPrototypeDisplayValue(new Country(0, "United States of America", ""));
        state.setPrototypeDisplayValue(new State(0, "Another Long State Name", ""));
        country.addActionListener(e -> { if (!loadingLists && country.getSelectedItem() != null) loadStates((Country) country.getSelectedItem(), 0, 0); });
        state.addActionListener(e -> { if (!loadingLists && state.getSelectedItem() != null) loadState((State) state.getSelectedItem(), 0); });
        counties.addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && !loadingLists) countyPicked(); });
        systems.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) next.setEnabled(systems.getSelectedValue() != null); });
        systems.addMouseListener(new java.awt.event.MouseAdapter() { public void mouseClicked(java.awt.event.MouseEvent e) { if (e.getClickCount() == 2 && systems.getSelectedValue() != null) openSystem(); } });
        systems.setCellRenderer(new DefaultListCellRenderer() {
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean fo) {
                super.getListCellRendererComponent(l, v, i, s, fo);
                TrsRef t = (TrsRef) v;
                setText("<html><b>" + Util.xml(t.name()) + "</b>" + (t.city().isBlank() ? "" : "<span style='color:#7D8FA3'> &nbsp;" + Util.xml(t.city()) + "</span>") + "</html>");
                setBorder(new EmptyBorder(5, 8, 5, 8)); return this;
            }
        });
        counties.setCellRenderer(new DefaultListCellRenderer() {
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean fo) {
                super.getListCellRendererComponent(l, v, i, s, fo);
                if (STATEWIDE.equals(v)) setFont(getFont().deriveFont(Font.ITALIC));
                setBorder(new EmptyBorder(4, 8, 4, 8)); return this;
            }
        });
        JPanel lists = new JPanel(new GridLayout(1, 2, 10, 0)); lists.setOpaque(false);
        lists.add(titled("County", counties)); lists.add(titled("Systems", systems));
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); bottom.setOpaque(false);
        bottom.add(dim("Or open it by ID:")); bottom.add(idKind); bottom.add(idField);
        JButton open = MainFrame.button("Open", null, Theme.CYAN); bottom.add(open);
        open.addActionListener(e -> openById()); idField.addActionListener(e -> openById());
        JButton conv = MainFrame.button("P25 conventional channels", null, Theme.CYAN);
        conv.setToolTipText("Look through this county's channel groups for P25 channels that stay on one frequency");
        conv.addActionListener(e -> findConventional());
        bottom.add(conv);
        JButton reload = MainFrame.button("Reload lists", Theme.Glyph.APPLY, Theme.DIM);
        reload.setToolTipText("Place lists are kept on this computer for " + RrSoap.CACHE_DAYS + " days; this asks RadioReference again");
        reload.addActionListener(e -> { RrSoap.clearCache(f.app.profile.resolve("rr-cache")); rr.cacheIn(f.app.profile.resolve("rr-cache")); clearSession(); loadCountries(); });
        bottom.add(Box.createHorizontalStrut(16)); bottom.add(reload);
        JPanel south = new JPanel(new BorderLayout(0, 6)); south.setOpaque(false);
        south.add(bottom, BorderLayout.NORTH);
        south.add(note("The system ID is the number at the end of the system page address on the RadioReference website (…/sid/<b>1234</b>). TrunkDeck decodes trunked P25 and conventional P25. Use <b>P25 conventional channels</b> for a county's fixed P25 frequencies."), BorderLayout.SOUTH);
        JPanel in = new JPanel(new BorderLayout(0, 10)); in.setOpaque(false);
        in.add(top, BorderLayout.NORTH); in.add(lists, BorderLayout.CENTER); in.add(south, BorderLayout.SOUTH);
        c.add(in, BorderLayout.CENTER);
        return c;
    }

    void clearSession() { if (rr != null) rr.forgetSession(); }

    int lastState() { try { return Integer.parseInt(f.app.settings.getProperty("rr.lastState", "0")); } catch (Exception e) { return 0; } }
    int lastCounty() { try { return Integer.parseInt(f.app.settings.getProperty("rr.lastCounty", "0")); } catch (Exception e) { return 0; } }

    void loadCountries() {
        run("Loading countries", () -> rr.getCountryList(), list -> {
            loadingLists = true;
            country.removeAllItems();
            Country pick = null;
            int want; try { want = Integer.parseInt(f.app.settings.getProperty("rr.lastCountry", "1")); } catch (Exception e) { want = 1; }
            for (Country c : list) { country.addItem(c); if (c.coid() == want) pick = c; }
            if (pick == null && !list.isEmpty()) pick = list.get(0);
            country.setSelectedItem(pick);
            loadingLists = false;
            if (pick != null) loadStates(pick, lastState(), lastCounty());
        });
    }

    void loadStates(Country c, int stid, int ctid) {
        run("Loading states", () -> rr.getCountryInfo(c.coid()), list -> {
            loadingLists = true;
            state.removeAllItems(); countyModel.clear(); systemModel.clear();
            State pick = null;
            for (State s : list) { state.addItem(s); if (s.stid() == stid) pick = s; }
            state.setSelectedItem(pick);
            if (pick == null) state.setSelectedIndex(-1);
            loadingLists = false;
            f.app.settings.setProperty("rr.lastCountry", String.valueOf(c.coid()));
            if (pick != null) loadState(pick, ctid); else info("Pick your state or province (or enter a ZIP code).");
        });
    }

    StateInfo stateInfo;

    void loadState(State s, int ctid) {
        run("Loading " + s.name(), () -> rr.getStateInfo(s.stid()), si -> {
            stateInfo = si;
            loadingLists = true;
            countyModel.clear(); systemModel.clear();
            if (!si.systems().isEmpty()) countyModel.addElement(STATEWIDE);
            Object pick = null;
            for (County c : si.counties()) { countyModel.addElement(c); if (c.ctid() == ctid) pick = c; }
            loadingLists = false;
            f.app.settings.setProperty("rr.lastState", String.valueOf(s.stid()));
            if (pick != null) { counties.setSelectedValue(pick, true); } else info("Pick your county.");
            next.setEnabled(false);
        });
    }

    void countyPicked() {
        Object v = counties.getSelectedValue();
        systemModel.clear(); next.setEnabled(false);
        if (v == null) return;
        if (STATEWIDE.equals(v)) { chosenCtid = 0; for (TrsRef t : stateInfo.systems()) systemModel.addElement(t); preselectSystem(); return; }
        County c = (County) v;
        run("Loading systems in " + c.name(), () -> rr.getCountyInfo(c.ctid()), ci -> {
            countyInfo = ci;
            convFreqs.clear();
            chosenCtid = c.ctid();
            f.app.settings.setProperty("rr.lastCounty", String.valueOf(c.ctid()));
            f.app.saveSettings();
            for (TrsRef t : ci.systems()) systemModel.addElement(t);
            if (ci.systems().isEmpty()) info("No trunked systems listed for " + Util.xml(c.name()) + " - try " + (stateInfo.systems().isEmpty() ? "a neighbouring county." : "\u201c" + STATEWIDE + "\u201d."));
            preselectSystem();
        });
    }

    void preselectSystem() {
        if (systemModel.size() == 1) systems.setSelectedIndex(0);
        next.setEnabled(systems.getSelectedValue() != null);
        if (systemModel.size() > 1) info("Pick your trunked system, then press Next.");
    }

    void findZip() {
        int z; try { z = Integer.parseInt(zip.getText().trim()); } catch (Exception e) { error("Enter a 5-digit ZIP code."); return; }
        run("Looking up ZIP code " + z, () -> rr.getZipcodeInfo(z), zi -> {
            for (int i = 0; i < state.getItemCount(); i++) if (state.getItemAt(i).stid() == zi.stid()) {
                loadingLists = true; state.setSelectedIndex(i); loadingLists = false;
                loadState(state.getItemAt(i), zi.ctid());
                return;
            }
            error("That ZIP code is not in the selected country's list.");
        });
    }

    void openById() {
        String v = idField.getText().trim();
        if (v.isEmpty()) { error("Enter a system ID."); return; }
        if (idKind.getSelectedIndex() == 0) {
            int sid; try { sid = Integer.parseInt(v.replaceAll("\\D", "")); } catch (Exception e) { error("A RadioReference system ID is a number, e.g. 1234 (the number at the end of the system page address on the RadioReference website)."); return; }
            fetchSystem(sid, 0, null);
        } else {
            String hex = v.replaceFirst("(?i)^0x", "");
            if (!hex.matches("(?i)[0-9a-f]{1,3}")) { error("A P25 System ID is 1-3 hex digits, e.g. 2D5."); return; }
            run("Searching for P25 System ID " + hex.toUpperCase(), () -> rr.getTrsBySysid(hex), list -> {
                if (list.isEmpty()) { error("RadioReference lists no system with P25 System ID " + hex.toUpperCase() + "."); return; }
                counties.clearSelection(); systemModel.clear();
                for (TrsRef t : list) systemModel.addElement(t);
                systems.setSelectedIndex(0);
                if (list.size() == 1) fetchSystem(list.get(0).sid(), 0, null); else info(list.size() + " systems use that System ID - pick yours.");
            });
        }
    }

    void openSystem() {
        TrsRef t = systems.getSelectedValue();
        if (t == null) { error("Pick a system first."); return; }
        if (t.sid() < 0) { openConventional(t); return; }
        pendingConv = null;
        fetchSystem(t.sid(), chosenCtid, null);
    }

    /** County groups that actually have P25 channels, added under the trunked systems. */
    void findConventional() {
        if (countyInfo == null || countyInfo.subcats().isEmpty()) { info("Pick a county first. If it has no channel groups, RadioReference has no conventional list to search."); return; }
        CountyInfo ci = countyInfo;
        run("Looking for P25 conventional channels", () -> {
            Map<Integer, List<Freq>> found = new LinkedHashMap<>();
            for (Subcat sc : ci.subcats()) {
                List<Freq> freqs = rr.getSubcatFreqs(sc.scid());
                if (!RrImport.p25Channels(freqs).isEmpty() || !RrImport.dmrChannels(freqs).isEmpty()) found.put(sc.scid(), freqs);
            }
            return found;
        }, found -> {
            convFreqs.clear();
            convFreqs.putAll(found);
            if (found.isEmpty()) { info("RadioReference lists no P25 conventional channels in " + ci.name() + "."); return; }
            int added = 0;
            for (Subcat sc : ci.subcats()) if (found.containsKey(sc.scid())) {
                String label = (sc.category().isBlank() ? "" : sc.category() + " — ") + sc.name();
                boolean have = false;
                for (int i = 0; i < systemModel.size(); i++) if (systemModel.get(i).sid() == -sc.scid()) have = true;
                if (!have) { systemModel.addElement(new TrsRef(-sc.scid(), label, 0, 0, 0, "P25 conventional")); added++; }
            }
            if (systemModel.size() == added) systems.setSelectedIndex(0);
            next.setEnabled(systems.getSelectedValue() != null);
            info(found.size() + " P25 conventional channel group" + (found.size() == 1 ? "" : "s") + ". Pick one and press Next.");
        });
    }

    void openConventional(TrsRef t) {
        List<Freq> freqs = convFreqs.get(-t.sid());
        if (freqs == null || (RrImport.p25Channels(freqs).isEmpty() && RrImport.dmrChannels(freqs).isEmpty())) { error("Those channels are no longer available. Press P25 conventional channels again."); return; }
        if (RrImport.p25Channels(freqs).isEmpty()) { pendingConv = RrImport.fromDmr(t.name(), freqs); fetched = null; prepareFinish(); go(4); info(pendingConv.channels.size() + " DMR channel" + (pendingConv.channels.size() == 1 ? "" : "s") + " (untested)."); return; }
        pendingConv = RrImport.fromConventional(t.name(), freqs);
        fetched = null;
        prepareFinish();
        go(4);
        info(pendingConv.channels.size() + " P25 channel" + (pendingConv.channels.size() == 1 ? "" : "s") + ".");
    }

    /** getTrsDetails + getTrsSites + getTrsTalkgroupCats + getTrsTalkgroups: the only system data fetched, on Next. */
    void fetchSystem(int sid, int ctid, String okText) {
        run("Downloading the system from RadioReference", () -> {
            TrsDetails d = rr.getTrsDetails(sid);
            String type = d.typeName().toLowerCase(Locale.ROOT);
            boolean dmrType = type.contains("dmr");
            if (!type.isEmpty() && !dmrType && !(type.contains("25") || type.contains("apco"))) throw new IllegalStateException(d.name() + " is a " + d.typeName() + " system. TrunkDeck decodes P25 and DMR systems.");
            return new RrImport.Fetched(d, rr.getTrsSites(sid), rr.getTrsTalkgroupCats(sid), rr.getTrsTalkgroups(sid));
        }, fd -> {
            fetched = fd;
            fillSites(ctid);
            go(2);
            info((okText == null ? "" : okText + " \u00b7 ") + Util.xml(fd.details().name()) + (fd.details().typeName().isBlank() ? "" : " (" + Util.xml(fd.details().typeName()) + ")")
                    + ": " + fd.sites().size() + " site" + (fd.sites().size() == 1 ? "" : "s") + ", " + fd.ordered().size() + " talkgroups");
        });
    }

    // ------------------------------------------------------------------ step 3: sites
    JComponent siteStep() {
        JPanel c = MainFrame.card("Pick the site(s) you can receive");
        styleTable(siteTable);
        siteTable.getColumnModel().getColumn(0).setMaxWidth(Layouts.s(40));
        siteTable.getColumnModel().getColumn(1).setPreferredWidth(Layouts.s(60));
        siteTable.getColumnModel().getColumn(2).setPreferredWidth(Layouts.s(200));
        siteTable.getColumnModel().getColumn(5).setPreferredWidth(Layouts.s(260));
        siteModel.addTableModelListener(e -> updateSiteNote());
        JPanel in = new JPanel(new BorderLayout(0, 8)); in.setOpaque(false);
        in.add(scroll(siteTable), BorderLayout.CENTER);
        siteNote.setFont(Theme.ui(Font.PLAIN, 12f));
        JPanel s = new JPanel(new BorderLayout(0, 4)); s.setOpaque(false);
        s.add(siteNote, BorderLayout.NORTH);
        s.add(note("Usually the nearest site. On a trunked system TrunkDeck tries the control channels in turn until one locks (primary first, then the alternates). "
                + "A conventional P25 system has no control channel: every frequency you tick is a channel TrunkDeck listens on."), BorderLayout.SOUTH);
        in.add(s, BorderLayout.SOUTH);
        c.add(in, BorderLayout.CENTER);
        return c;
    }

    void fillSites(int ctid) {
        siteModel.conventional = RrImport.conventionalSystem(fetched.details());
        siteModel.sites = new ArrayList<>(fetched.sites());
        siteModel.on = new boolean[siteModel.sites.size()];
        Set<String> want = new HashSet<>();
        if (refresh != null) for (String x : refresh.rrSites.split(",")) want.add(x.trim());
        Site obvious = refresh != null ? null : RrImport.obvious(siteModel.sites, ctid);
        for (int i = 0; i < siteModel.sites.size(); i++) siteModel.on[i] = siteModel.sites.get(i) == obvious || want.contains(String.valueOf(siteModel.sites.get(i).siteId()));
        siteModel.fireTableDataChanged();
        updateSiteNote();
    }

    List<Site> chosenSites() { List<Site> l = new ArrayList<>(); for (int i = 0; i < siteModel.sites.size(); i++) if (siteModel.on[i]) l.add(siteModel.sites.get(i)); return l; }

    void updateSiteNote() {
        List<Site> ch = chosenSites();
        boolean conv = siteModel.conventional;
        List<Double> cc = conv ? channelFreqs(RrImport.conventionalChannels(ch)) : RrImport.controlChannels(ch);
        siteNote.setForeground(ch.isEmpty() ? Theme.AMBER : Theme.TEXT);
        siteNote.setText(ch.isEmpty() ? "No site chosen yet." : "<html>" + ch.size() + " site" + (ch.size() == 1 ? "" : "s") + (conv ? " \u00b7 channels: " : " \u00b7 control channels: ") + "<b>" + freqs(cc) + "</b> MHz</html>");
    }

    void sitesChosen() {
        List<Site> ch = chosenSites();
        if (ch.isEmpty()) { error("Tick at least one site."); return; }
        List<Double> have = siteModel.conventional ? channelFreqs(RrImport.conventionalChannels(ch)) : RrImport.controlChannels(ch);
        if (have.isEmpty()) { error("RadioReference lists no frequencies for that site - pick another one."); return; }
        fillTalkgroups();
        go(3);
    }

    // ------------------------------------------------------------------ step 4: talkgroups
    JComponent talkgroupStep() {
        JPanel c = MainFrame.card("Choose talkgroups");
        styleTable(tgTable);
        tgTable.setDefaultRenderer(Object.class, new TgRenderer());
        tgTable.setDefaultRenderer(Boolean.class, new TgCheckRenderer());
        tgTable.getColumnModel().getColumn(0).setMaxWidth(Layouts.s(40));
        tgTable.getColumnModel().getColumn(1).setPreferredWidth(Layouts.s(70));
        tgTable.getColumnModel().getColumn(2).setPreferredWidth(Layouts.s(170));
        tgTable.getColumnModel().getColumn(3).setPreferredWidth(Layouts.s(250));
        tgTable.getColumnModel().getColumn(5).setPreferredWidth(Layouts.s(150));
        tgModel.addTableModelListener(e -> updateCount());
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)); tools.setOpaque(false);
        JButton clear = MainFrame.button("All except encrypted", null, Theme.GREEN), all = MainFrame.button("All", null, Theme.DIM), none = MainFrame.button("None", null, Theme.DIM);
        clear.addActionListener(e -> tgModel.setAll(false)); all.addActionListener(e -> tgModel.setAll(true)); none.addActionListener(e -> tgModel.setNone());
        tools.add(clear); tools.add(all); tools.add(none); tools.add(Box.createHorizontalStrut(12)); tools.add(tgCount);
        tgCount.setFont(Theme.ui(Font.PLAIN, 12f));
        JPanel in = new JPanel(new BorderLayout(0, 8)); in.setOpaque(false);
        JPanel north = new JPanel(new BorderLayout(0, 6)); north.setOpaque(false);
        tgNote.setFont(Theme.ui(Font.PLAIN, 12f));
        north.add(tgNote, BorderLayout.NORTH); north.add(tools, BorderLayout.SOUTH);
        in.add(north, BorderLayout.NORTH);
        in.add(scroll(tgTable), BorderLayout.CENTER);
        JPanel s = new JPanel(new BorderLayout(0, 4)); s.setOpaque(false);
        updateNames.setOpaque(false); updateNames.setVisible(refresh != null);
        s.add(updateNames, BorderLayout.NORTH);
        s.add(note("Grouped by RadioReference category - tick a category to tick all of it. <span style='color:#B388FF'>Encrypted</span> talkgroups "
                + "can't be heard, so they start unticked (if you add them anyway, TrunkDeck still skips encrypted calls). \u201cPartly encrypted\u201d ones "
                + "are ticked: their clear calls play."), BorderLayout.SOUTH);
        in.add(s, BorderLayout.SOUTH);
        c.add(in, BorderLayout.CENTER);
        return c;
    }

    void fillTalkgroups() {
        List<Tg> list = refresh != null ? RrImport.newOnRr(refresh, fetched) : fetched.ordered();
        tgModel.load(list, fetched);
        if (refresh == null) tgNote.setText("<html>" + Util.xml(fetched.details().name()) + " has <b>" + list.size() + "</b> talkgroups on RadioReference.</html>");
        else tgNote.setText("<html>" + (list.isEmpty() ? "No new talkgroups on RadioReference since " + (refresh.rrUpdated.isBlank() ? "the last import" : refresh.rrUpdated) + "."
                : "<b>" + list.size() + "</b> new talkgroup" + (list.size() == 1 ? "" : "s") + " on RadioReference since " + (refresh.rrUpdated.isBlank() ? "the last import" : refresh.rrUpdated) + " - choose which to add.")
                + " The " + refresh.talkgroups.size() + " you already have are updated; none is removed.</html>");
        updateCount();
    }

    void updateCount() {
        int on = 0, enc = 0, n = 0;
        for (TgModel.Row r : tgModel.rows) if (r.tg != null) { n++; if (r.on) on++; if (r.tg.encrypted()) enc++; }
        tgCount.setText("<html><b>" + on + "</b> of " + n + " chosen" + (enc > 0 ? " \u00b7 <span style='color:#B388FF'>" + enc + " encrypted</span>" : "") + "</html>");
    }

    // ------------------------------------------------------------------ step 5: finish
    JComponent finishStep() {
        JPanel c = MainFrame.card(refresh == null ? "Create the system" : "Apply the update");
        JPanel g = form(); GridBagConstraints k = gbc();
        if (refresh == null) { addRow(g, k, "System name", sysName); startNow.setOpaque(false); addRow(g, k, "", startNow); }
        finishText.setEditable(false); finishText.setOpaque(false);
        finishText.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        finishText.setFont(Theme.ui(Font.PLAIN, 13f)); finishText.setForeground(Theme.TEXT);
        k.gridx = 0; k.gridwidth = 2; k.gridy++; k.weightx = 1; k.weighty = 1; k.fill = GridBagConstraints.BOTH;
        JScrollPane sp = new JScrollPane(finishText); sp.setBorder(BorderFactory.createEmptyBorder()); sp.setOpaque(false); sp.getViewport().setOpaque(false);
        g.add(sp, k);
        c.add(g, BorderLayout.CENTER);
        return c;
    }

    Set<Integer> chosenTalkgroups() { Set<Integer> s = new LinkedHashSet<>(); for (TgModel.Row r : tgModel.rows) if (r.tg != null && r.on) s.add(r.tg.dec()); return s; }

    /** True when the system being set up matches a site the user already saved (same WACN and System ID, or same RadioReference system). */
    boolean sharesExisting() {
        if (fetched == null) return false;
        RadioSystem probe = new RadioSystem();
        probe.rrSid = fetched.details().sid();
        if (RrImport.conventionalSystem(fetched.details())) probe.kind = RadioSystem.CONVENTIONAL;
        if (!fetched.details().sysids().isEmpty()) {
            probe.sysId = fetched.details().sysids().get(0).sysid();
            probe.wacn = fetched.details().sysids().get(0).wacn();
        }
        for (RadioSystem e : f.app.systems) if (TalkgroupLists.sameSystem(probe, e)) return true;
        return false;
    }

    void prepareFinish() {
        if (pendingConv != null) { prepareConventionalFinish(); return; }
        List<Site> sites = chosenSites();
        Set<Integer> tgs = chosenTalkgroups();
        StringBuilder h = new StringBuilder("<html><body style='font-family:sans-serif'>");
        if (refresh == null) {
            if (sysName.getText().isBlank()) sysName.setText(RrImport.defaultName(fetched, sites));
            boolean running = f.running();
            startNow.setSelected(!running); startNow.setEnabled(!running);
            startNow.setText(running ? "Start monitoring it now (TrunkDeck is monitoring \u201c" + (f.app.current == null ? "" : f.app.current.name) + "\u201d - switch from the System box at the top when you're ready)" : "Start monitoring it now");
            Set<String> cats = new TreeSet<>(); int enc = 0;
            for (Tg t : fetched.ordered()) if (tgs.contains(t.dec())) { cats.add(fetched.category(t.tgCid())); if (t.encrypted()) enc++; }
            boolean conv = RrImport.conventionalSystem(fetched.details());
            List<Double> shown = conv ? channelFreqs(RrImport.conventionalChannels(sites)) : RrImport.controlChannels(sites);
            h.append("<p>TrunkDeck will create <b>").append(Util.xml(sysName.getText().isBlank() ? RrImport.defaultName(fetched, sites) : sysName.getText())).append("</b>:</p><ul>")
             .append("<li>").append(conv ? "Channels" : "Control channels").append(": <b>").append(freqs(shown)).append("</b> MHz (").append(sites.size()).append(" site").append(sites.size() == 1 ? "" : "s").append(")</li>")
             .append("<li><b>").append(tgs.size()).append("</b> talkgroups in ").append(cats.size()).append(" categories, with names, tags and categories")
             .append(enc > 0 ? " (" + enc + " encrypted - their calls are skipped)" : "").append("</li>")
             .append("<li>Usual settings: demodulator Auto (C4FM and LSM), up to 20 calls at once. Change them on the Systems page any time.</li>")
             .append(sharesExisting() ? "<li>This site shares the talkgroup list you already keep for this system. RadioReference names are merged in; favorites, mutes, record, priority and streams stay.</li>" : "")
             .append("<li>New talkgroups are not sent to any stream until you assign them (Talkgroups or Streams page).</li></ul>")
             .append("<p style='color:#7D8FA3'>Nothing else is fetched from RadioReference until you choose Refresh on the Systems page.</p>");
        } else {
            boolean current = refresh == f.app.current, running = current && f.running();
            List<Double> cc = RrImport.controlChannels(sites);
            boolean ccChange = !cc.equals(refresh.controlChannelsMHz);
            h.append("<p>Updating <b>").append(Util.xml(refresh.name)).append("</b>:</p><ul>")
             .append("<li>").append(refresh.talkgroups.size()).append(" talkgroups you have: names, descriptions, tags, categories and modes updated from RadioReference; ")
             .append("your favourites, mutes, recording, priorities and stream assignments stay as they are; none is removed.</li>")
             .append("<li>").append(tgs.size()).append(" new talkgroup").append(tgs.size() == 1 ? "" : "s").append(" added.</li>")
             .append("<li>Control channels: ").append(ccChange ? "<b>" + freqs(refresh.controlChannelsMHz) + " \u2192 " + freqs(cc) + "</b> MHz" : "unchanged (" + freqs(cc) + " MHz)").append("</li>")
             .append(TalkgroupLists.sharing(f.app.systems, refresh).size() > 1
                     ? "<li>Other sites on this system use this same list, so they pick up the same names and settings.</li>" : "")
             .append("</ul>");
            if (running) h.append("<p>Talkgroup changes apply at once while monitoring; feeds and uploads keep running.")
                    .append(ccChange ? " The new control channels need the radio part restarted: TrunkDeck shows <b>Restart</b> in the top bar - press it when it suits you (feeds and uploads stay connected)." : "").append("</p>");
        }
        finishText.setText(h.append("</body></html>").toString());
        finishText.setCaretPosition(0);
    }

    void prepareConventionalFinish() {
        RadioSystem s = pendingConv;
        if (sysName.getText().isBlank()) sysName.setText(s.name);
        boolean running = f.running();
        startNow.setSelected(!running); startNow.setEnabled(!running);
        startNow.setText(running ? "Start monitoring it now (TrunkDeck is already monitoring another system)" : "Start monitoring it now");
        StringBuilder h = new StringBuilder("<html><body style='font-family:sans-serif'><p>TrunkDeck will create <b>")
                .append(Util.xml(sysName.getText().isBlank() ? s.name : sysName.getText())).append("</b>, a conventional P25 system:</p><ul>")
                .append("<li>Channels: <b>").append(freqs(channelFreqs(s.channels))).append("</b> MHz</li>")
                .append("<li><b>").append(s.talkgroups.size()).append("</b> talkgroup").append(s.talkgroups.size() == 1 ? "" : "s").append(" named from RadioReference</li>")
                .append("<li>No control channel. TrunkDeck listens on these frequencies and decodes P25 voice. Encrypted calls are skipped.</li></ul></body></html>");
        finishText.setText(h.toString());
        finishText.setCaretPosition(0);
    }

    void apply() {
        if (pendingConv != null) { applyConventional(); return; }
        List<Site> sites = chosenSites();
        Set<Integer> tgs = chosenTalkgroups();
        if (refresh == null) {
            RadioSystem s = RrImport.build(fetched, sites, tgs, sysName.getText());
            s.id = f.app.uniqueId(RadioSystem.safeId(s.name));
            try { f.app.save(s); } catch (Exception ex) { error("Could not save the system: " + ex.getMessage()); return; }
            f.app.systems.add(s);
            f.app.bindTalkgroups();   // a site of a system the user already has joins that talkgroup list
            f.app.systems.sort(Comparator.comparing(x -> x.name.toLowerCase()));
            boolean start = startNow.isSelected() && startNow.isEnabled() && !f.running();
            if (start || f.app.current == null) { f.app.current = s; f.app.settings.setProperty("lastSystem", s.id); f.app.saveSettings(); }
            f.reloadSystems();
            f.systemsPage.select(start || f.app.current == s ? s : f.app.current);
            if (f.rrBanner != null) f.dismissRrOffer();
            done = true;
            boolean sharedList = TalkgroupLists.sharing(f.app.systems, s).size() > 1;
            finishText.setText("<html><body style='font-family:sans-serif'><p><b style='color:#2BFF88'>\u2713 " + Util.xml(s.name) + " is ready</b> - "
                    + s.talkgroups.size() + " talkgroups, " + (s.conventional() ? "channels " : "control channels ") + freqs(s.frequencies()) + " MHz.</p>"
                    + (sharedList ? "<p>It uses the talkgroup list already kept for this system, so names, favorites, mutes, record, priority and streams stay in one place.</p>" : "")
                    + (start ? "<p>Monitoring starts now.</p>" : f.app.current == s ? "" : "<p>It's in the System box at the top: pick it when you want to listen to it (switching restarts the radio part; feeds stay connected).</p>")
                    + "<p style='color:#7D8FA3'>Check it any time on the Systems and Talkgroups pages. To update it later: Systems page \u2192 Refresh from RadioReference.</p></body></html>");
            finishText.setCaretPosition(0);
            go(4);
            f.toast("Set up " + s.name + " from RadioReference (" + s.talkgroups.size() + " talkgroups).");
            if (start) { dispose(); f.show("Monitor"); SwingUtilities.invokeLater(f::startMonitoring); }
        } else {
            boolean current = refresh == f.app.current;
            String before = SystemsPage.restartKey(refresh);
            RrImport.Summary sum = RrImport.merge(refresh, fetched, sites, tgs, updateNames.isSelected());
            f.app.bindTalkgroups();                    // a changed WACN, System ID or RadioReference id may join or leave the shared list
            boolean needsRestart = !before.equals(SystemsPage.restartKey(refresh));
            if (current) {
                f.talkgroupsEdited();                  // live: the engine gets the new list now, nothing restarts
                f.talkgroups.setSystem(refresh);
                f.display.setSystem(refresh);
            }
            try { f.saveTalkgroupsNow(true); f.app.save(refresh); } catch (Exception ex) { error("Could not save: " + ex.getMessage()); return; }
            if (current && needsRestart && f.running()) f.markDirty();
            f.systemsPage.refreshList(); f.systemsPage.select(refresh);
            done = true;
            String restart = current && needsRestart && f.running()
                    ? "<p><b style='color:#FFB300'>The control channels changed.</b> Press <b>Restart</b> in the top bar when it suits you - feeds and uploads stay connected.</p>"
                    : needsRestart && current ? "<p>The new control channels are used the next time you start monitoring.</p>" : "";
            finishText.setText("<html><body style='font-family:sans-serif'><p><b style='color:#2BFF88'>\u2713 " + Util.xml(refresh.name) + " updated from RadioReference</b></p>" + summaryHtml(sum.text()) + restart + "</body></html>");
            finishText.setCaretPosition(0);
            go(4);
            f.toast("Updated " + refresh.name + " from RadioReference: " + sum.added.size() + " new, " + sum.renamed.size() + " renamed" + (needsRestart && f.running() && current ? " - press Restart for the new control channels (feeds stay connected)" : "") + ".");
        }
    }

    void applyConventional() {
        RadioSystem s = pendingConv;
        s.name = sysName.getText().isBlank() ? s.name : sysName.getText().trim();
        s.id = f.app.uniqueId(RadioSystem.safeId(s.name));
        try { f.app.save(s); } catch (Exception ex) { error("Could not save the system: " + ex.getMessage()); return; }
        f.app.systems.add(s);
        f.app.bindTalkgroups();
        f.app.systems.sort(Comparator.comparing(x -> x.name.toLowerCase()));
        boolean start = startNow.isSelected() && startNow.isEnabled() && !f.running();
        if (start || f.app.current == null) { f.app.current = s; f.app.settings.setProperty("lastSystem", s.id); f.app.saveSettings(); }
        f.reloadSystems();
        f.systemsPage.select(start || f.app.current == s ? s : f.app.current);
        if (f.rrBanner != null) f.dismissRrOffer();
        done = true;
        pendingConv = null;
        finishText.setText("<html><body style='font-family:sans-serif'><p><b style='color:#2BFF88'>\u2713 " + Util.xml(s.name) + " is ready</b> - "
                + s.channels.size() + " channel" + (s.channels.size() == 1 ? "" : "s") + ", " + freqs(s.frequencies()) + " MHz.</p>"
                + (start ? "<p>Monitoring starts now.</p>" : "") + "</body></html>");
        finishText.setCaretPosition(0);
        go(4);
        f.toast("Set up " + s.name + " (" + s.channels.size() + " conventional channels).");
        if (start) { dispose(); f.show("Monitor"); SwingUtilities.invokeLater(f::startMonitoring); }
    }

    // ------------------------------------------------------------------ tables
    static final class SiteModel extends AbstractTableModel {
        List<Site> sites = new ArrayList<>(); boolean[] on = new boolean[0];
        boolean conventional;
        final String[] cols = {"", "Site", "Name", "County", "NAC", "Control channels (MHz)", "Modulation"};
        public String getColumnName(int c) { return c == 5 && conventional ? "Channels (MHz)" : cols[c]; }
        public int getRowCount() { return sites.size(); }
        public int getColumnCount() { return cols.length; }
        public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
        public boolean isCellEditable(int r, int c) { return c == 0; }
        public Object getValueAt(int r, int c) {
            Site s = sites.get(r);
            return switch (c) {
                case 0 -> on[r]; case 1 -> s.label(); case 2 -> s.descr(); case 3 -> s.county(); case 4 -> s.nac();
                case 5 -> {
                    if (conventional) yield freqs(channelFreqs(RrImport.conventionalChannels(List.of(s))));
                    List<Double> cc = s.controlChannels();
                    yield cc.isEmpty() ? "(none marked - all " + s.freqs().size() + " frequencies)" : freqs(cc);
                }
                default -> s.modulation();
            };
        }
        public void setValueAt(Object v, int r, int c) { if (c == 0) { on[r] = (Boolean) v; fireTableRowsUpdated(r, r); } }
    }

    static final class TgModel extends AbstractTableModel {
        static final class Row { String cat; Tg tg; boolean on; }
        final List<Row> rows = new ArrayList<>();
        final String[] cols = {"", "TG", "Alpha tag", "Description", "Tag", "Mode"};
        void load(List<Tg> list, RrImport.Fetched f) {
            rows.clear();
            Set<Integer> def = RrImport.defaultSelection(list);
            String last = null;
            for (Tg t : list) {
                String c = f.category(t.tgCid());
                if (!c.equals(last)) { Row h = new Row(); h.cat = c.isBlank() ? "(no category)" : c; rows.add(h); last = c; }
                Row r = new Row(); r.tg = t; r.on = def.contains(t.dec()); rows.add(r);
            }
            fireTableDataChanged();
        }
        void setAll(boolean evenEncrypted) { for (Row r : rows) if (r.tg != null) r.on = evenEncrypted || !r.tg.encrypted(); fireTableDataChanged(); }
        void setNone() { for (Row r : rows) r.on = false; fireTableDataChanged(); }
        boolean catOn(int headerRow) {
            boolean any = false;
            for (int i = headerRow + 1; i < rows.size() && rows.get(i).tg != null; i++) { if (!rows.get(i).on) return false; any = true; }
            return any;
        }
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return cols.length; }
        public String getColumnName(int c) { return cols[c]; }
        public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : Object.class; }
        public boolean isCellEditable(int r, int c) { return c == 0; }
        public Object getValueAt(int r, int c) {
            Row x = rows.get(r);
            if (x.tg == null) return c == 0 ? catOn(r) : c == 1 ? x.cat : "";
            Tg t = x.tg;
            return switch (c) {
                case 0 -> x.on; case 1 -> String.valueOf(t.dec()); case 2 -> t.alpha(); case 3 -> t.descr();
                case 4 -> t.tags().isEmpty() ? "" : t.tags().get(0);
                default -> t.encrypted() ? "Encrypted (" + RrImport.mode(t) + ")" : t.sometimesEncrypted() ? "Partly encrypted (" + RrImport.mode(t) + ")" : RrImport.mode(t);
            };
        }
        public void setValueAt(Object v, int r, int c) {
            if (c != 0) return;
            boolean b = (Boolean) v;
            Row x = rows.get(r);
            if (x.tg == null) for (int i = r + 1; i < rows.size() && rows.get(i).tg != null; i++) rows.get(i).on = b;
            else x.on = b;
            fireTableDataChanged();
        }
    }

    /** One slice of a category row's label (JTable has no cell spans). */
    static final class Span extends JComponent {
        String text = ""; int offset;
        Span() { setOpaque(true); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(getBackground()); g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(Theme.ui(Font.BOLD, 12f)); g2.setColor(Theme.CYAN);
            FontMetrics m = g2.getFontMetrics();
            g2.drawString(text, Layouts.s(6) - offset, (getHeight() + m.getAscent() - m.getDescent()) / 2);
            g2.dispose();
        }
    }
    final Span span = new Span();

    final class TgRenderer extends DefaultTableCellRenderer {
        public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean fo, int r, int c) {
            super.getTableCellRendererComponent(t, v, s, false, r, c);
            TgModel.Row x = tgModel.rows.get(r);
            setBorder(new EmptyBorder(0, 6, 0, 6));
            if (x.tg == null) {
                // the category name runs across the row: each cell paints its slice of one long label
                int n = 0; for (int i = r + 1; i < tgModel.rows.size() && tgModel.rows.get(i).tg != null; i++) n++;
                span.text = x.cat + "   \u00b7 " + n + " talkgroup" + (n == 1 ? "" : "s");
                int x0 = 0; for (int i = 1; i < c; i++) x0 += t.getColumnModel().getColumn(i).getWidth();
                span.offset = x0;
                span.setBackground(s ? t.getSelectionBackground() : Theme.PANEL2);
                return span;
            }
            setFont(c == 1 ? Theme.mono(Font.PLAIN, 12f) : Theme.ui(Font.PLAIN, 12f));
            setForeground(x.tg.encrypted() ? Theme.VIOLET : c == 5 && x.tg.sometimesEncrypted() ? Theme.AMBER : c >= 3 ? Theme.DIM : Theme.TEXT);
            if (!s) setBackground(Theme.PANEL);
            return this;
        }
    }

    final class TgCheckRenderer implements TableCellRenderer {
        final JCheckBox b = new JCheckBox();
        { b.setHorizontalAlignment(SwingConstants.CENTER); b.setBorderPainted(false); b.setOpaque(true); }
        public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean fo, int r, int c) {
            b.setSelected(Boolean.TRUE.equals(v));
            TgModel.Row x = tgModel.rows.get(r);
            b.setBackground(s ? t.getSelectionBackground() : x.tg == null ? Theme.PANEL2 : Theme.PANEL);
            return b;
        }
    }

    /** The merge summary as a list (indented lines are details of the line above). */
    static String summaryHtml(String text) {
        StringBuilder h = new StringBuilder("<ul>");
        boolean sub = false;
        for (String line : text.split("\n")) {
            if (line.startsWith("    ")) { if (!sub) { h.append("<ul>"); sub = true; } h.append("<li style='color:#7D8FA3'>").append(Util.xml(line.strip())).append("</li>"); }
            else { if (sub) { h.append("</ul>"); sub = false; } h.append("<li>").append(Util.xml(line)).append("</li>"); }
        }
        if (sub) h.append("</ul>");
        return h.append("</ul>").toString();
    }

    // ------------------------------------------------------------------ small helpers
    static String freqs(List<Double> l) { StringJoiner j = new StringJoiner(", "); for (double d : l) j.add(RadioSystem.fmt(d)); return l.isEmpty() ? "none" : j.toString(); }
    static List<Double> channelFreqs(List<RadioSystem.Channel> ch) { List<Double> l = new ArrayList<>(); for (RadioSystem.Channel c : ch) l.add(c.mhz); return l; }
    static JPanel form() { JPanel g = new JPanel(new GridBagLayout()); g.setOpaque(false); return g; }
    static GridBagConstraints gbc() { GridBagConstraints k = new GridBagConstraints(); k.insets = new Insets(5, 4, 5, 8); k.fill = GridBagConstraints.HORIZONTAL; k.anchor = GridBagConstraints.WEST; k.gridy = -1; return k; }
    static void addRow(JPanel g, GridBagConstraints k, String label, JComponent c) {
        k.gridy++; k.gridx = 0; k.weightx = 0; k.gridwidth = 1;
        JLabel l = new JLabel(label); l.setFont(Theme.ui(Font.BOLD, 12f)); g.add(l, k);
        k.gridx = 1; k.weightx = 1; g.add(c, k);
    }
    static JLabel note(String html) {
        JLabel l = new JLabel("<html><body style='width:" + Layouts.s(600) + "px'><span style='color:#7D8FA3;font-size:10px'>" + html + "</span></body></html>");
        return l;
    }
    static JLabel dim(String t) { JLabel l = new JLabel(t); l.setForeground(Theme.DIM); return l; }
    static JComponent titled(String t, JComponent list) {
        JPanel p = new JPanel(new BorderLayout(0, 4)); p.setOpaque(false);
        JLabel l = new JLabel(t.toUpperCase()); l.setFont(Theme.mono(Font.BOLD, 11f)); l.setForeground(Theme.DIM);
        p.add(l, BorderLayout.NORTH); p.add(scroll(list), BorderLayout.CENTER);
        return p;
    }
    static JScrollPane scroll(JComponent c) { JScrollPane s = new JScrollPane(c); s.setBorder(new LineBorder(Theme.LINE)); return s; }
    static void styleTable(JTable t) {
        t.setRowHeight(Layouts.s(24)); t.setShowHorizontalLines(false); t.setShowVerticalLines(false);
        t.setFillsViewportHeight(true); t.getTableHeader().setReorderingAllowed(false);
        t.setIntercellSpacing(new Dimension(0, 0));
    }
}
