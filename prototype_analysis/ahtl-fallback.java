package defpackage;

/* JADX INFO: compiled from: PG */
/* JADX INFO: loaded from: G:\Dev\BlueBit\prototype_analysis\tmp-dex\classes6.dex */
public final class ahtl implements defpackage.aheu {
    public static final long a = 0;
    public final android.content.Context b;
    public final pnc c;
    public final bzzf d;
    public final bzvu e;
    public final defpackage.akqm f;
    private final yqw g;
    private final bzri h;
    private final bzjh i;
    private final boolean j;
    private boolean k;
    private final ptc l;
    private final abjr m;

    static {
            long r0 = bzpw.a
            r0 = 20
            bzpy r1 = bzpy.d
            long r0 = bzje.G(r0, r1)
            defpackage.ahtl.a = r0
            return
    }

    public ahtl(android.content.Context r7, pnc r8, ptc r9, yqw r10, defpackage.akqm r11, abjr r12, bzri r13, bzjh r14, defpackage.ahfx r15, boolean r16) {
            r6 = this;
            r6.<init>()
            r6.b = r7
            r6.c = r8
            r6.l = r9
            r6.g = r10
            r6.f = r11
            r6.m = r12
            r6.h = r13
            r6.i = r14
            r7 = r16
            r6.j = r7
            ahsz r7 = defpackage.ahsz.a
            bzzf r7 = caab.a(r7)
            r6.d = r7
            io.reactivex.subjects.Subject r8 = r8.b()
            bzvu r8 = bpov.be(r8)
            bcwo r9 = new bcwo
            r10 = 4
            r9.<init>(r8, r10)
            ahtb r8 = new ahtb
            r10 = 0
            r8.<init>(r10)
            bzyy r11 = new bzyy
            r11.<init>(r9, r7, r8)
            agsa r7 = new agsa
            r8 = 18
            r7.<init>(r11, r6, r8)
            ahtc r8 = new ahtc
            r8.<init>(r10)
            bzwz r1 = new bzwz
            r1.<init>(r8, r7)
            r7 = r15
            ahtn r7 = (defpackage.ahtn) r7
            j$.time.Duration r7 = r7.c
            long r2 = r7.toMillis()
            ahse r4 = new ahse
            r7 = 3
            r4.<init>(r7)
            ahay r0 = new ahay
            r5 = 0
            r0.<init>(r1, r2, r4, r5)
            bzvn r7 = new bzvn
            r7.<init>(r0)
            bzvu r7 = bzmh.bA(r7, r14)
            r6.e = r7
            return
    }

    @Override // defpackage.aheu
    public final java.lang.Object a(bzjc r13) {
            r12 = this;
            boolean r0 = r13 instanceof defpackage.ahth
            if (r0 == 0) goto L13
            r0 = r13
            ahth r0 = (defpackage.ahth) r0
            int r1 = r0.label
            r2 = -2147483648(0xffffffff80000000, float:-0.0)
            r3 = r1 & r2
            if (r3 == 0) goto L13
            int r1 = r1 - r2
            r0.label = r1
            goto L18
        L13:
            ahth r0 = new ahth
            r0.<init>(r12, r13)
        L18:
            java.lang.Object r13 = r0.result
            bzjk r1 = bzjk.a
            int r2 = r0.label
            r3 = 0
            r4 = 4
            r5 = 2
            r6 = 3
            r7 = 1
            r8 = 0
            if (r2 == 0) goto L4b
            if (r2 == r7) goto L47
            if (r2 == r5) goto L43
            if (r2 == r6) goto L3b
            if (r2 != r4) goto L33
            bwwj.dw(r13)
            goto Lab
        L33:
            java.lang.IllegalStateException r12 = new java.lang.IllegalStateException
            java.lang.String r13 = "call to 'resume' before 'invoke' with coroutine"
            r12.<init>(r13)
            throw r12
        L3b:
            java.lang.Object r2 = r0.L$0
            java.lang.String r2 = (java.lang.String) r2
            bwwj.dw(r13)
            goto L7d
        L43:
            bwwj.dw(r13)
            goto L63
        L47:
            bwwj.dw(r13)
            goto L5a
        L4b:
            bwwj.dw(r13)
            ptc r13 = r12.l
            r0.label = r7
            java.lang.Object r13 = r13.a(r0)
            if (r13 != r1) goto L5a
            goto Ld3
        L5a:
            r0.label = r5
            java.lang.Object r13 = r12.c(r0)
            if (r13 != r1) goto L63
            goto Ld3
        L63:
            pnc r13 = r12.c
            java.lang.String r2 = r13.c()
            if (r2 != 0) goto L71
            ahfu r12 = new ahfu
            r12.<init>(r3)
            return r12
        L71:
            abjr r13 = r12.m
            r0.L$0 = r2
            r0.label = r6
            java.lang.Object r13 = r13.c(r0)
            if (r13 == r1) goto Ld3
        L7d:
            zck r13 = (zck) r13
            yqw r13 = r12.g
            bzvu r13 = r13.g(r2)
            agwc r5 = new agwc
            r9 = 18
            r5.<init>(r13, r9)
            ahtj r13 = new ahtj
            r13.<init>(r8)
            yus r9 = new yus
            r9.<init>(r5, r13, r6)
            long r10 = defpackage.ahtl.a
            ahtk r13 = new ahtk
            r13.<init>(r2, r8)
            bzvu r13 = alhs.da(r9, r10, r13)
            r0.L$0 = r8
            r0.label = r4
            java.lang.Object r13 = bzmh.bp(r13, r0)
            if (r13 == r1) goto Ld3
        Lab:
            boolean r0 = r12.j
            yqq r13 = (yqq) r13
            if (r0 == 0) goto Lc3
            if (r13 == 0) goto Lc3
            yqv r0 = r13.o
            boolean r0 = r0.E
            if (r0 != r7) goto Lc3
            bzri r0 = r12.h
            ahti r1 = new ahti
            r1.<init>(r12, r13, r8)
            bzje.y(r0, r8, r3, r1, r6)
        Lc3:
            if (r13 != 0) goto Lcb
            ahfu r12 = new ahfu
            r12.<init>(r7)
            return r12
        Lcb:
            ahfv r0 = new ahfv
            boolean r12 = r12.k
            r0.<init>(r13, r12)
            return r0
        Ld3:
            return r1
    }

    public final java.lang.Object b(pnb r5, bzjc r6) {
            r4 = this;
            boolean r0 = r6 instanceof defpackage.ahtf
            if (r0 == 0) goto L13
            r0 = r6
            ahtf r0 = (defpackage.ahtf) r0
            int r1 = r0.label
            r2 = -2147483648(0xffffffff80000000, float:-0.0)
            r3 = r1 & r2
            if (r3 == 0) goto L13
            int r1 = r1 - r2
            r0.label = r1
            goto L18
        L13:
            ahtf r0 = new ahtf
            r0.<init>(r4, r6)
        L18:
            java.lang.Object r6 = r0.result
            bzjk r1 = bzjk.a
            int r2 = r0.label
            r3 = 1
            if (r2 == 0) goto L30
            if (r2 != r3) goto L28
            bwwj.dw(r6)
            goto L127
        L28:
            java.lang.IllegalStateException r4 = new java.lang.IllegalStateException
            java.lang.String r5 = "call to 'resume' before 'invoke' with coroutine"
            r4.<init>(r5)
            throw r4
        L30:
            bwwj.dw(r6)
            java.lang.String r6 = r5.b
            pmo r6 = pmo.a
            boolean r6 = bzlr.h(r5, r6)
            if (r6 == 0) goto L40
            ahfm r4 = defpackage.ahfm.a
            return r4
        L40:
            pms r6 = pms.a
            boolean r6 = bzlr.h(r5, r6)
            if (r6 == 0) goto L4b
            ahfc r4 = defpackage.ahfc.a
            return r4
        L4b:
            pmj r6 = pmj.a
            boolean r6 = bzlr.h(r5, r6)
            if (r6 == 0) goto L56
            ahex r4 = defpackage.ahex.a
            return r4
        L56:
            pmk r6 = pmk.a
            boolean r6 = bzlr.h(r5, r6)
            if (r6 == 0) goto L63
            r4.k = r3
            ahey r4 = defpackage.ahey.a
            return r4
        L63:
            pmi r6 = pmi.a
            boolean r6 = bzlr.h(r5, r6)
            if (r6 == 0) goto L72
            ahew r4 = new ahew
            r5 = 0
            r4.<init>(r5)
            return r4
        L72:
            boolean r6 = r5 instanceof pmn
            if (r6 == 0) goto Ld0
            pmn r5 = (pmn) r5
            pld r4 = r5.a
            pjy r5 = pjy.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L85
            ahfa r4 = defpackage.ahfa.a
            return r4
        L85:
            pkc r5 = pkc.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L90
            ahfe r4 = defpackage.ahfe.a
            return r4
        L90:
            pkj r5 = pkj.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L9b
            ahfd r4 = defpackage.ahfd.a
            return r4
        L9b:
            pkg r5 = pkg.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto La6
            ahfb r4 = defpackage.ahfb.a
            return r4
        La6:
            pkw r5 = pkw.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto Lb1
            ahfo r4 = defpackage.ahfo.a
            return r4
        Lb1:
            pjt r5 = pjt.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto Lbf
            ahew r4 = new ahew
            r4.<init>(r3)
            return r4
        Lbf:
            pjs r5 = pjs.a
            boolean r4 = bzlr.h(r4, r5)
            if (r4 == 0) goto Lcd
            ahew r4 = new ahew
            r4.<init>(r3)
            return r4
        Lcd:
            ahfq r4 = defpackage.ahfq.a
            return r4
        Ld0:
            pmw r6 = pmw.a
            boolean r6 = bzlr.h(r5, r6)
            if (r6 != 0) goto L19b
            pmv r6 = pmv.a
            boolean r6 = bzlr.h(r5, r6)
            if (r6 == 0) goto Le2
            goto L19b
        Le2:
            pmu r6 = pmu.a
            boolean r6 = bzlr.h(r5, r6)
            if (r6 == 0) goto Led
            ahfi r4 = defpackage.ahfi.a
            return r4
        Led:
            pmm r6 = pmm.a
            boolean r6 = bzlr.h(r5, r6)
            r2 = 2
            if (r6 == 0) goto L10c
            pnc r4 = r4.c
            pih r4 = r4.a()
            ahsy r4 = (defpackage.ahsy) r4
            pif r4 = r4.c
            int r4 = r4.ordinal()
            if (r4 == r2) goto L109
            ahfs r4 = defpackage.ahfs.a
            return r4
        L109:
            ahfr r4 = defpackage.ahfr.a
            return r4
        L10c:
            pna r6 = pna.a
            boolean r6 = bzlr.h(r5, r6)
            if (r6 == 0) goto L117
            ahfj r4 = defpackage.ahfj.a
            return r4
        L117:
            pmt r6 = pmt.a
            boolean r6 = bzlr.h(r5, r6)
            if (r6 == 0) goto L130
            r0.label = r3
            java.lang.Object r6 = r4.a(r0)
            if (r6 == r1) goto L12f
        L127:
            ahfw r6 = (defpackage.ahfw) r6
            ahfh r4 = new ahfh
            r4.<init>(r6)
            return r4
        L12f:
            return r1
        L130:
            pmy r6 = pmy.a
            boolean r6 = bzlr.h(r5, r6)
            if (r6 == 0) goto L141
            pnc r4 = r4.c
            r5 = 0
            r4.g(r5)
            ahfp r4 = defpackage.ahfp.a
            return r4
        L141:
            pmp r6 = pmp.a
            boolean r6 = bzlr.h(r5, r6)
            if (r6 == 0) goto L14c
            ahff r4 = defpackage.ahff.a
            return r4
        L14c:
            pmq r6 = pmq.a
            boolean r6 = bzlr.h(r5, r6)
            if (r6 == 0) goto L175
            bzzf r4 = r4.d
            java.lang.Object r4 = r4.b()
            ahsz r4 = (defpackage.ahsz) r4
            int r4 = r4.ordinal()
            if (r4 == 0) goto L172
            if (r4 == r3) goto L16f
            if (r4 != r2) goto L169
            ahfl r4 = defpackage.ahfl.a
            return r4
        L169:
            kotlin.NoWhenBranchMatchedException r4 = new kotlin.NoWhenBranchMatchedException
            r4.<init>()
            throw r4
        L16f:
            ahfk r4 = defpackage.ahfk.a
            return r4
        L172:
            ahez r4 = defpackage.ahez.a
            return r4
        L175:
            pml r4 = pml.a
            boolean r4 = bzlr.h(r5, r4)
            if (r4 != 0) goto L198
            pmx r4 = pmx.a
            boolean r4 = bzlr.h(r5, r4)
            if (r4 != 0) goto L198
            pmr r4 = pmr.a
            boolean r4 = bzlr.h(r5, r4)
            if (r4 != 0) goto L198
            boolean r4 = r5 instanceof pmz
            if (r4 == 0) goto L192
            goto L198
        L192:
            kotlin.NoWhenBranchMatchedException r4 = new kotlin.NoWhenBranchMatchedException
            r4.<init>()
            throw r4
        L198:
            ahfq r4 = defpackage.ahfq.a
            return r4
        L19b:
            ahfg r4 = defpackage.ahfg.a
            return r4
    }

    public final java.lang.Object c(bzjc r6) {
            r5 = this;
            boolean r0 = r6 instanceof defpackage.ahtg
            if (r0 == 0) goto L13
            r0 = r6
            ahtg r0 = (defpackage.ahtg) r0
            int r1 = r0.label
            r2 = -2147483648(0xffffffff80000000, float:-0.0)
            r3 = r1 & r2
            if (r3 == 0) goto L13
            int r1 = r1 - r2
            r0.label = r1
            goto L18
        L13:
            ahtg r0 = new ahtg
            r0.<init>(r5, r6)
        L18:
            java.lang.Object r6 = r0.result
            bzjk r1 = bzjk.a
            int r2 = r0.label
            r3 = 2
            r4 = 1
            if (r2 == 0) goto L3a
            if (r2 == r4) goto L32
            if (r2 != r3) goto L2a
            bwwj.dw(r6)     // Catch: java.lang.Exception -> L79
            goto L75
        L2a:
            java.lang.IllegalStateException r5 = new java.lang.IllegalStateException
            java.lang.String r6 = "call to 'resume' before 'invoke' with coroutine"
            r5.<init>(r6)
            throw r5
        L32:
            java.lang.Object r5 = r0.L$0
            android.content.Context r5 = (android.content.Context) r5
            bwwj.dw(r6)     // Catch: java.lang.Exception -> L79
            goto L56
        L3a:
            bwwj.dw(r6)
            android.content.Context r5 = r5.b     // Catch: java.lang.Exception -> L79
            java.lang.Class<asyo> r6 = asyo.class
            java.lang.Object r6 = bsmv.p(r5, r6)     // Catch: java.lang.Exception -> L79
            asyo r6 = (asyo) r6     // Catch: java.lang.Exception -> L79
            tgd r6 = r6.gP()     // Catch: java.lang.Exception -> L79
            r0.L$0 = r5     // Catch: java.lang.Exception -> L79
            r0.label = r4     // Catch: java.lang.Exception -> L79
            java.lang.Object r6 = r6.a(r0)     // Catch: java.lang.Exception -> L79
            if (r6 != r1) goto L56
            goto L78
        L56:
            com.google.apps.tiktok.account.AccountId r6 = (com.google.apps.tiktok.account.AccountId) r6     // Catch: java.lang.Exception -> L79
            r2 = 0
            if (r6 == 0) goto L62
            java.lang.Class<ahta> r4 = defpackage.ahta.class
            java.lang.Object r5 = bmkk.Z(r5, r4, r6)     // Catch: java.lang.Exception -> L79
            goto L63
        L62:
            r5 = r2
        L63:
            ahta r5 = (defpackage.ahta) r5     // Catch: java.lang.Exception -> L79
            if (r5 == 0) goto L79
            tvi r5 = r5.gf()     // Catch: java.lang.Exception -> L79
            r0.L$0 = r2     // Catch: java.lang.Exception -> L79
            r0.label = r3     // Catch: java.lang.Exception -> L79
            java.lang.Object r6 = r5.a(r0)     // Catch: java.lang.Exception -> L79
            if (r6 == r1) goto L78
        L75:
            bzgp r6 = (bzgp) r6     // Catch: java.lang.Exception -> L79
            goto L79
        L78:
            return r1
        L79:
            bzgp r5 = bzgp.a
            return r5
    }
}
