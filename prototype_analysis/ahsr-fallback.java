package defpackage;

/* JADX INFO: compiled from: PG */
/* JADX INFO: loaded from: G:\Dev\BlueBit\prototype_analysis\tmp-dex\classes6.dex */
public final class ahsr implements pnc {
    public final pih a;
    public final io.reactivex.subjects.Subject b;
    public final qge c;
    public final bzku d;
    public final com.fitbit.bluetooth.fbgatt.rx.client.GattServiceRefresher e;
    public final int f;
    public final j$.time.Duration g;
    public final java.util.concurrent.atomic.AtomicInteger h;
    public nrq i;
    public pgp j;
    public java.lang.String k;
    public final defpackage.ahsd l;
    public final ptg m;
    public final defpackage.abnb n;
    private final defpackage.aher o;
    private final bzri p;
    private bzsq q;
    private final pxk r;
    private final pht s;

    public ahsr(pih r1, defpackage.aher r2, defpackage.ahsd r3, defpackage.abnb r4, io.reactivex.subjects.Subject r5, qge r6, bzri r7, bzku r8, com.fitbit.bluetooth.fbgatt.rx.client.GattServiceRefresher r9, ptg r10, pxk r11, pht r12, int r13, j$.time.Duration r14) {
            r0 = this;
            r0.<init>()
            r0.a = r1
            r0.o = r2
            r0.l = r3
            r0.n = r4
            r0.b = r5
            r0.c = r6
            r0.p = r7
            r0.d = r8
            r0.e = r9
            r0.m = r10
            r0.r = r11
            r0.s = r12
            r0.f = r13
            r0.g = r14
            java.util.concurrent.atomic.AtomicInteger r1 = new java.util.concurrent.atomic.AtomicInteger
            r2 = 0
            r1.<init>(r2)
            r0.h = r1
            pgv r1 = new pgv
            r1.<init>()
            r0.j = r1
            return
    }

    public final pih a() {
            r0 = this;
            pih r0 = r0.a
            return r0
    }

    public final io.reactivex.subjects.Subject b() {
            r0 = this;
            io.reactivex.subjects.Subject r0 = r0.b
            return r0
    }

    public final java.lang.String c() {
            r0 = this;
            java.lang.String r0 = r0.k
            return r0
    }

    public final java.lang.String d() {
            r0 = this;
            nrq r0 = r0.i
            if (r0 == 0) goto Ld
            nqt r0 = r0.d()
            if (r0 == 0) goto Ld
            java.lang.String r0 = r0.b
            return r0
        Ld:
            r0 = 0
            return r0
    }

    public final void e() {
            r2 = this;
            java.util.concurrent.atomic.AtomicInteger r0 = r2.h
            r1 = 0
            r0.set(r1)
            bzsq r2 = r2.q
            if (r2 == 0) goto Le
            r0 = 0
            r2.t(r0)
        Le:
            return
    }

    public final void f() {
            r4 = this;
            ahsk r0 = new ahsk
            r1 = 0
            r0.<init>(r4, r1)
            bzri r4 = r4.p
            r2 = 0
            r3 = 3
            bzje.y(r4, r1, r2, r0, r3)
            return
    }

    public final void g(java.lang.String r4) {
            r3 = this;
            ahsl r4 = new ahsl
            r0 = 0
            r4.<init>(r3, r0)
            bzri r3 = r3.p
            r1 = 0
            r2 = 3
            bzje.y(r3, r0, r1, r4, r2)
            return
    }

    public final void h(android.content.Context r4) {
            r3 = this;
            ahso r0 = new ahso
            r1 = 0
            r0.<init>(r3, r4, r1)
            bzri r3 = r3.p
            r4 = 0
            r2 = 3
            bzje.y(r3, r1, r4, r0, r2)
            return
    }

    public final void i(android.content.Context r9) {
            r8 = this;
            java.util.concurrent.atomic.AtomicInteger r0 = r8.h
            r0.get()
            pih r1 = r8.a
            ahsy r1 = (defpackage.ahsy) r1
            java.lang.String r1 = r1.b
            abnb r2 = r8.n
            java.lang.Object r2 = r2.a
            int r0 = r0.get()
            onc r2 = (onc) r2
            r3 = 11
            r4 = 0
            r2.g(r3, r1, r4)
            cahb r1 = cahb.a
            buzi r1 = r1.createBuilder()
            r1.getClass()
            r1.copyOnWrite()
            com.google.protobuf.GeneratedMessageLite r3 = r1.instance
            cahb r3 = (cahb) r3
            r5 = 2
            r3.c = r5
            int r6 = r3.b
            r6 = r6 | 1
            r3.b = r6
            com.google.protobuf.GeneratedMessageLite r1 = r1.build()
            r1.getClass()
            java.lang.Object r2 = r2.b
            cahb r1 = (cahb) r1
            r3 = r2
            buzi r3 = (buzi) r3
            r3.copyOnWrite()
            cagz r2 = (cagz) r2
            com.google.protobuf.GeneratedMessageLite r6 = r2.instance
            caha r6 = (caha) r6
            caha r7 = caha.a
            r6.K = r1
            int r1 = r6.c
            r1 = r1 | r5
            r6.c = r1
            r3.copyOnWrite()
            com.google.protobuf.GeneratedMessageLite r1 = r2.instance
            caha r1 = (caha) r1
            int r6 = r1.c
            r7 = 4194304(0x400000, float:5.877472E-39)
            r6 = r6 | r7
            r1.c = r6
            r1.ac = r0
            r3.copyOnWrite()
            com.google.protobuf.GeneratedMessageLite r0 = r2.instance
            caha r0 = (caha) r0
            r0.T = r5
            int r1 = r0.c
            r1 = r1 | 2048(0x800, float:2.87E-42)
            r0.c = r1
            r3.copyOnWrite()
            com.google.protobuf.GeneratedMessageLite r0 = r2.instance
            caha r0 = (caha) r0
            r0.y = r5
            int r1 = r0.b
            r2 = 2097152(0x200000, float:2.938736E-39)
            r1 = r1 | r2
            r0.b = r1
            ahsp r0 = new ahsp
            r0.<init>(r8, r9, r4)
            bzri r9 = r8.p
            r1 = 0
            r2 = 3
            bzsq r9 = bzje.y(r9, r4, r1, r0, r2)
            r8.q = r9
            return
    }

    public final java.lang.Object j(defpackage.ahsd r8, java.lang.String r9, bzjc r10) {
            r7 = this;
            boolean r0 = r10 instanceof defpackage.ahsf
            if (r0 == 0) goto L13
            r0 = r10
            ahsf r0 = (defpackage.ahsf) r0
            int r1 = r0.label
            r2 = -2147483648(0xffffffff80000000, float:-0.0)
            r3 = r1 & r2
            if (r3 == 0) goto L13
            int r1 = r1 - r2
            r0.label = r1
            goto L18
        L13:
            ahsf r0 = new ahsf
            r0.<init>(r7, r10)
        L18:
            java.lang.Object r10 = r0.result
            bzjk r1 = bzjk.a
            int r2 = r0.label
            r3 = 3
            r4 = 2
            r5 = 1
            r6 = 0
            if (r2 == 0) goto L4f
            if (r2 == r5) goto L42
            if (r2 == r4) goto L3a
            if (r2 != r3) goto L32
            java.lang.Object r7 = r0.L$0
            ahes r7 = (defpackage.ahes) r7
            bwwj.dw(r10)
            return r7
        L32:
            java.lang.IllegalStateException r7 = new java.lang.IllegalStateException
            java.lang.String r8 = "call to 'resume' before 'invoke' with coroutine"
            r7.<init>(r8)
            throw r7
        L3a:
            java.lang.Object r8 = r0.L$0
            ahsd r8 = (defpackage.ahsd) r8
            bwwj.dw(r10)
            goto L8a
        L42:
            java.lang.Object r8 = r0.L$1
            r9 = r8
            java.lang.String r9 = (java.lang.String) r9
            java.lang.Object r8 = r0.L$0
            ahsd r8 = (defpackage.ahsd) r8
            bwwj.dw(r10)
            goto L6c
        L4f:
            bwwj.dw(r10)
            int r10 = r9.length()
            if (r10 == 0) goto La2
            ahsd r10 = r7.l
            r2 = 8
            r10.j(r2)
            r0.L$0 = r8
            r0.L$1 = r9
            r0.label = r5
            java.lang.Object r10 = r8.a(r0)
            if (r10 != r1) goto L6c
            goto La1
        L6c:
            ahsd r2 = r7.l
            byte[] r10 = (byte[]) r10
            r5 = 9
            r2.j(r5)
            aher r2 = r7.o
            r0.L$0 = r8
            r0.L$1 = r6
            r0.label = r4
            ahst r4 = new ahst
            ahsx r2 = (defpackage.ahsx) r2
            r4.<init>(r2, r9, r10, r6)
            java.lang.Object r10 = r2.a(r4, r0)
            if (r10 == r1) goto La1
        L8a:
            ahsd r7 = r7.l
            ahes r10 = (defpackage.ahes) r10
            r9 = 10
            r7.j(r9)
            byte[] r7 = r10.b
            r0.L$0 = r10
            r0.label = r3
            java.lang.Object r7 = r8.e(r7, r0)
            if (r7 != r1) goto La0
            goto La1
        La0:
            return r10
        La1:
            return r1
        La2:
            com.fitbit.fbcomms.pairing.PairingFailureError r7 = new com.fitbit.fbcomms.pairing.PairingFailureError
            pkc r8 = pkc.a
            r7.<init>(r8, r6)
            throw r7
    }

    public final java.lang.Object k(defpackage.ahsd r10, bzjc r11) {
            r9 = this;
            boolean r0 = r11 instanceof defpackage.ahsg
            if (r0 == 0) goto L13
            r0 = r11
            ahsg r0 = (defpackage.ahsg) r0
            int r1 = r0.label
            r2 = -2147483648(0xffffffff80000000, float:-0.0)
            r3 = r1 & r2
            if (r3 == 0) goto L13
            int r1 = r1 - r2
            r0.label = r1
            goto L18
        L13:
            ahsg r0 = new ahsg
            r0.<init>(r9, r11)
        L18:
            java.lang.Object r11 = r0.result
            bzjk r1 = bzjk.a
            int r2 = r0.label
            r3 = 1
            r4 = 0
            r5 = 3
            r6 = 2
            if (r2 == 0) goto L47
            if (r2 == r3) goto L3f
            if (r2 == r6) goto L36
            if (r2 != r5) goto L2e
            bwwj.dw(r11)
            return r11
        L2e:
            java.lang.IllegalStateException r9 = new java.lang.IllegalStateException
            java.lang.String r10 = "call to 'resume' before 'invoke' with coroutine"
            r9.<init>(r10)
            throw r9
        L36:
            java.lang.Object r10 = r0.L$0
            ahsd r10 = (defpackage.ahsd) r10
            bwwj.dw(r11)
            goto Lca
        L3f:
            java.lang.Object r10 = r0.L$0
            ahsd r10 = (defpackage.ahsd) r10
            bwwj.dw(r11)
            goto L59
        L47:
            bwwj.dw(r11)
            ahsd r11 = r9.l
            r11.j(r6)
            r0.L$0 = r10
            r0.label = r3
            java.lang.Object r11 = r10.b(r0)
            if (r11 == r1) goto Lde
        L59:
            byte[] r11 = (byte[]) r11
            bwja r2 = bwja.a
            com.google.protobuf.GeneratedMessageLite r2 = com.google.protobuf.GeneratedMessageLite.parseFrom(r2, r11)
            bwja r2 = (bwja) r2
            bvag r2 = r2.b
            r2.getClass()
            java.lang.Object r2 = bwwj.bb(r2)
            bwjc r2 = (bwjc) r2
            if (r2 == 0) goto L91
            java.lang.StringBuilder r3 = new java.lang.StringBuilder
            r3.<init>()
            int r7 = r2.b
            r3.append(r7)
            r7 = 46
            r3.append(r7)
            int r8 = r2.c
            r3.append(r8)
            r3.append(r7)
            int r2 = r2.d
            r3.append(r2)
            java.lang.String r2 = r3.toString()
            goto L92
        L91:
            r2 = r4
        L92:
            if (r2 == 0) goto Lb2
            ahsd r3 = r9.l
            java.lang.Object r3 = r3.a
            onc r3 = (onc) r3
            java.lang.Object r3 = r3.b
            r7 = r3
            buzi r7 = (buzi) r7
            r7.copyOnWrite()
            cagz r3 = (cagz) r3
            com.google.protobuf.GeneratedMessageLite r3 = r3.instance
            caha r3 = (caha) r3
            caha r7 = caha.a
            int r7 = r3.b
            r7 = r7 | 512(0x200, float:7.175E-43)
            r3.b = r7
            r3.m = r2
        Lb2:
            ahsd r2 = r9.l
            r2.j(r5)
            aher r2 = r9.o
            r0.L$0 = r10
            r0.label = r6
            ahsu r3 = new ahsu
            ahsx r2 = (defpackage.ahsx) r2
            r3.<init>(r2, r11, r4)
            java.lang.Object r11 = r2.a(r3, r0)
            if (r11 == r1) goto Lde
        Lca:
            ahsd r9 = r9.l
            byte[] r11 = (byte[]) r11
            r2 = 4
            r9.j(r2)
            r0.L$0 = r4
            r0.label = r5
            java.lang.Object r9 = r10.f(r11, r0)
            if (r9 != r1) goto Ldd
            goto Lde
        Ldd:
            return r9
        Lde:
            return r1
    }

    public final java.lang.Object l(bzjc r10) {
            r9 = this;
            boolean r0 = r10 instanceof defpackage.ahsh
            if (r0 == 0) goto L13
            r0 = r10
            ahsh r0 = (defpackage.ahsh) r0
            int r1 = r0.label
            r2 = -2147483648(0xffffffff80000000, float:-0.0)
            r3 = r1 & r2
            if (r3 == 0) goto L13
            int r1 = r1 - r2
            r0.label = r1
            goto L18
        L13:
            ahsh r0 = new ahsh
            r0.<init>(r9, r10)
        L18:
            r6 = r0
            java.lang.Object r10 = r6.result
            bzjk r0 = bzjk.a
            int r1 = r6.label
            r2 = 1
            if (r1 == 0) goto L34
            if (r1 != r2) goto L2c
            java.lang.Object r9 = r6.L$0
            ahsr r9 = (defpackage.ahsr) r9
            bwwj.dw(r10)
            goto L8b
        L2c:
            java.lang.IllegalStateException r9 = new java.lang.IllegalStateException
            java.lang.String r10 = "call to 'resume' before 'invoke' with coroutine"
            r9.<init>(r10)
            throw r9
        L34:
            bwwj.dw(r10)
            nrq r10 = r9.i
            r1 = 0
            if (r10 == 0) goto L92
            nqt r3 = r10.d()
            if (r3 == 0) goto L45
            android.bluetooth.BluetoothDevice r3 = r3.a
            goto L46
        L45:
            r3 = r1
        L46:
            pih r4 = r9.a
            ahsy r4 = (defpackage.ahsy) r4
            int[] r4 = r4.a
            r5 = 74
            boolean r4 = bzhf.da(r4, r5)
            if (r4 != r2) goto L67
            if (r3 == 0) goto L67
            int r3 = r3.getBondState()
            r4 = 12
            if (r3 == r4) goto L5f
            goto L67
        L5f:
            com.fitbit.fbcomms.pairing.PairingFailureError r9 = new com.fitbit.fbcomms.pairing.PairingFailureError
            pjs r10 = pjs.a
            r9.<init>(r10, r1)
            throw r9
        L67:
            r3 = r1
            j$.time.Duration r1 = r9.g
            r4 = r2
            int r2 = r9.f
            r5 = r3
            ahse r3 = new ahse
            r7 = 0
            r3.<init>(r7)
            r7 = r4
            ahsi r4 = new ahsi
            r4.<init>(r9, r10, r5)
            r8 = r5
            ahsj r5 = new ahsj
            r5.<init>(r9, r10, r8)
            r6.L$0 = r9
            r6.label = r7
            java.lang.Object r10 = vaw.h(r1, r2, r3, r4, r5, r6)
            if (r10 != r0) goto L8b
            return r0
        L8b:
            pgp r10 = (pgp) r10
            r9.j = r10
            bzgp r9 = bzgp.a
            return r9
        L92:
            r8 = r1
            io.reactivex.subjects.Subject r9 = r9.b
            pmr r10 = pmr.a
            r9.onNext(r10)
            com.fitbit.fbcomms.pairing.PairingFailureError r9 = new com.fitbit.fbcomms.pairing.PairingFailureError
            pkf r10 = pkf.a
            r9.<init>(r10, r8)
            throw r9
    }

    public final java.lang.Object m(defpackage.ahsd r7, bzjc r8) {
            r6 = this;
            boolean r0 = r8 instanceof defpackage.ahsm
            if (r0 == 0) goto L13
            r0 = r8
            ahsm r0 = (defpackage.ahsm) r0
            int r1 = r0.label
            r2 = -2147483648(0xffffffff80000000, float:-0.0)
            r3 = r1 & r2
            if (r3 == 0) goto L13
            int r1 = r1 - r2
            r0.label = r1
            goto L18
        L13:
            ahsm r0 = new ahsm
            r0.<init>(r6, r8)
        L18:
            java.lang.Object r8 = r0.result
            bzjk r1 = bzjk.a
            int r2 = r0.label
            r3 = 3
            r4 = 2
            r5 = 1
            if (r2 == 0) goto L49
            if (r2 == r5) goto L41
            if (r2 == r4) goto L39
            if (r2 != r3) goto L31
            java.lang.Object r6 = r0.L$0
            ahet r6 = (defpackage.ahet) r6
            bwwj.dw(r8)
            return r6
        L31:
            java.lang.IllegalStateException r6 = new java.lang.IllegalStateException
            java.lang.String r7 = "call to 'resume' before 'invoke' with coroutine"
            r6.<init>(r7)
            throw r6
        L39:
            java.lang.Object r7 = r0.L$0
            ahsd r7 = (defpackage.ahsd) r7
            bwwj.dw(r8)
            goto L78
        L41:
            java.lang.Object r7 = r0.L$0
            ahsd r7 = (defpackage.ahsd) r7
            bwwj.dw(r8)
            goto L5c
        L49:
            bwwj.dw(r8)
            ahsd r8 = r6.l
            r2 = 5
            r8.j(r2)
            r0.L$0 = r7
            r0.label = r5
            java.lang.Object r8 = r7.c(r0)
            if (r8 == r1) goto Lab
        L5c:
            ahsd r2 = r6.l
            byte[] r8 = (byte[]) r8
            r5 = 6
            r2.j(r5)
            aher r2 = r6.o
            r0.L$0 = r7
            r0.label = r4
            ahsv r4 = new ahsv
            ahsx r2 = (defpackage.ahsx) r2
            r5 = 0
            r4.<init>(r2, r8, r5)
            java.lang.Object r8 = r2.a(r4, r0)
            if (r8 == r1) goto Lab
        L78:
            ahsd r6 = r6.l
            ahet r8 = (defpackage.ahet) r8
            java.lang.String r2 = r8.a
            java.lang.Object r4 = r6.a
            onc r4 = (onc) r4
            java.lang.Object r4 = r4.b
            r5 = r4
            buzi r5 = (buzi) r5
            r5.copyOnWrite()
            cagz r4 = (cagz) r4
            com.google.protobuf.GeneratedMessageLite r4 = r4.instance
            caha r4 = (caha) r4
            caha r5 = caha.a
            int r5 = r4.b
            r5 = r5 | 8
            r4.b = r5
            r4.g = r2
            r2 = 7
            r6.j(r2)
            byte[] r6 = r8.b
            r0.L$0 = r8
            r0.label = r3
            java.lang.Object r6 = r7.g(r6, r0)
            if (r6 == r1) goto Lab
            return r8
        Lab:
            return r1
    }

    public final java.lang.Object n(defpackage.ahsd r5, bzjc r6) {
            r4 = this;
            boolean r0 = r6 instanceof defpackage.ahsn
            if (r0 == 0) goto L13
            r0 = r6
            ahsn r0 = (defpackage.ahsn) r0
            int r1 = r0.label
            r2 = -2147483648(0xffffffff80000000, float:-0.0)
            r3 = r1 & r2
            if (r3 == 0) goto L13
            int r1 = r1 - r2
            r0.label = r1
            goto L18
        L13:
            ahsn r0 = new ahsn
            r0.<init>(r4, r6)
        L18:
            java.lang.Object r4 = r0.result
            bzjk r6 = bzjk.a
            int r1 = r0.label
            r2 = 1
            if (r1 == 0) goto L2f
            if (r1 != r2) goto L27
            bwwj.dw(r4)     // Catch: java.lang.Throwable -> L3e
            goto L3a
        L27:
            java.lang.IllegalStateException r4 = new java.lang.IllegalStateException
            java.lang.String r5 = "call to 'resume' before 'invoke' with coroutine"
            r4.<init>(r5)
            throw r4
        L2f:
            bwwj.dw(r4)
            r0.label = r2     // Catch: java.lang.Throwable -> L3e
            java.lang.Object r4 = r5.d(r0)     // Catch: java.lang.Throwable -> L3e
            if (r4 == r6) goto L3d
        L3a:
            bzgp r4 = bzgp.a     // Catch: java.lang.Throwable -> L3e
            goto L43
        L3d:
            return r6
        L3e:
            r4 = move-exception
            java.lang.Object r4 = bwwj.dv(r4)
        L43:
            boolean r5 = bzgf.e(r4)
            if (r5 == 0) goto L4c
            r5 = r4
            bzgp r5 = (bzgp) r5
        L4c:
            bzgf.d(r4)
            bzgp r4 = bzgp.a
            return r4
    }

    public final java.lang.Object o(java.lang.String r20, pgp r21, bzjc r22) {
            r19 = this;
            r0 = r19
            r1 = r21
            r2 = r22
            boolean r3 = r2 instanceof defpackage.ahsq
            if (r3 == 0) goto L19
            r3 = r2
            ahsq r3 = (defpackage.ahsq) r3
            int r4 = r3.label
            r5 = -2147483648(0xffffffff80000000, float:-0.0)
            r6 = r4 & r5
            if (r6 == 0) goto L19
            int r4 = r4 - r5
            r3.label = r4
            goto L1e
        L19:
            ahsq r3 = new ahsq
            r3.<init>(r0, r2)
        L1e:
            java.lang.Object r2 = r3.result
            bzjk r4 = bzjk.a
            int r5 = r3.label
            r6 = 12
            r7 = 4
            r8 = 3
            r9 = 2
            r10 = 1
            r11 = 0
            if (r5 == 0) goto L6b
            if (r5 == r10) goto L5f
            if (r5 == r9) goto L4e
            if (r5 == r8) goto L41
            if (r5 != r7) goto L39
            bwwj.dw(r2)
            return r2
        L39:
            java.lang.IllegalStateException r0 = new java.lang.IllegalStateException
            java.lang.String r1 = "call to 'resume' before 'invoke' with coroutine"
            r0.<init>(r1)
            throw r0
        L41:
            java.lang.Object r1 = r3.L$1
            ajyn r1 = (defpackage.ajyn) r1
            java.lang.Object r5 = r3.L$0
            pgp r5 = (pgp) r5
            bwwj.dw(r2)
            goto Ld7
        L4e:
            java.lang.Object r1 = r3.L$1
            pgp r1 = (pgp) r1
            java.lang.Object r5 = r3.L$0
            java.lang.String r5 = (java.lang.String) r5
            bwwj.dw(r2)
        L59:
            r18 = r5
            r5 = r1
            r1 = r18
            goto Lae
        L5f:
            java.lang.Object r1 = r3.L$1
            pgp r1 = (pgp) r1
            java.lang.Object r5 = r3.L$0
            java.lang.String r5 = (java.lang.String) r5
            bwwj.dw(r2)
            goto L89
        L6b:
            bwwj.dw(r2)
            ahsd r2 = r0.l
            r2.j(r6)
            pgr r2 = pgr.a
            pjn r5 = pjn.d
            bxsz r2 = r1.getMegaDump(r2, r11, r5)
            r5 = r20
            r3.L$0 = r5
            r3.L$1 = r1
            r3.label = r10
            java.lang.Object r2 = bxmx.p(r2, r3)
            if (r2 == r4) goto Lfe
        L89:
            pht r12 = r0.s
            r13 = r2
            byte[] r13 = (byte[]) r13
            r13.getClass()
            pih r2 = r0.a
            ahsy r2 = (defpackage.ahsy) r2
            java.lang.String r15 = r2.b
            sxr r14 = sxr.a
            sxs r16 = sxs.a
            r17 = 0
            bxsz r2 = r12.b(r13, r14, r15, r16, r17)
            r3.L$0 = r5
            r3.L$1 = r1
            r3.label = r9
            java.lang.Object r2 = bxmx.p(r2, r3)
            if (r2 == r4) goto Lfe
            goto L59
        Lae:
            ajyn r2 = (defpackage.ajyn) r2
            java.lang.Object r9 = r2.c
            r3.L$0 = r5
            r3.L$1 = r2
            r3.label = r8
            java.lang.String r8 = r0.d()
            if (r8 == 0) goto Ld2
            if (r1 == 0) goto Ld2
            ahsd r8 = r0.l
            r10 = 11
            r8.j(r10)
            pxk r8 = r0.r
            bxrt r1 = r8.a(r9, r1)
            java.lang.Object r1 = bxmx.o(r1, r3)
            goto Ld4
        Ld2:
            bzgp r1 = bzgp.a
        Ld4:
            if (r1 == r4) goto Lfe
            r1 = r2
        Ld7:
            ahsd r0 = r0.l
            r0.j(r6)
            pia r0 = new pia
            r1.getClass()
            phz r2 = phz.a
            r0.<init>(r1, r2)
            io.reactivex.subjects.BehaviorSubject r1 = io.reactivex.subjects.BehaviorSubject.create()
            pgr r2 = pgr.a
            bxrt r0 = r5.sendDump(r0, r1, r2, r11)
            r3.L$0 = r11
            r3.L$1 = r11
            r3.label = r7
            java.lang.Object r0 = bxmx.o(r0, r3)
            if (r0 != r4) goto Lfd
            goto Lfe
        Lfd:
            return r0
        Lfe:
            return r4
    }

    public final void p(java.lang.Throwable r7, pnb r8) {
            r6 = this;
            ahsd r0 = r6.l
            java.lang.Object r0 = r0.a
            onc r0 = (onc) r0
            r0.e(r7)
            boolean r1 = r7 instanceof com.fitbit.fbcomms.pairing.PairingFailureError
            if (r1 == 0) goto L218
            r2 = r7
            com.fitbit.fbcomms.pairing.PairingFailureError r2 = (com.fitbit.fbcomms.pairing.PairingFailureError) r2
            java.lang.Object r0 = r0.b
            r3 = r0
            buzi r3 = (buzi) r3
            r3.copyOnWrite()
            cagz r0 = (cagz) r0
            com.google.protobuf.GeneratedMessageLite r4 = r0.instance
            caha r4 = (caha) r4
            caha r5 = caha.a
            r5 = 3
            r4.o = r5
            int r5 = r4.b
            r5 = r5 | 2048(0x800, float:2.87E-42)
            r4.b = r5
            pld r4 = r2.reason
            r4.getClass()
            pju r5 = pju.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L3a
            cahd r4 = cahd.k
            goto L1db
        L3a:
            pjv r5 = pjv.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L46
            cahd r4 = cahd.g
            goto L1db
        L46:
            pjw r5 = pjw.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L52
            cahd r4 = cahd.z
            goto L1db
        L52:
            pjx r5 = pjx.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L5e
            cahd r4 = cahd.e
            goto L1db
        L5e:
            pjy r5 = pjy.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L6a
            cahd r4 = cahd.B
            goto L1db
        L6a:
            pjz r5 = pjz.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L76
            cahd r4 = cahd.V
            goto L1db
        L76:
            pka r5 = pka.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L82
            cahd r4 = cahd.W
            goto L1db
        L82:
            pkb r5 = pkb.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L8e
            cahd r4 = cahd.U
            goto L1db
        L8e:
            pkc r5 = pkc.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L9a
            cahd r4 = cahd.w
            goto L1db
        L9a:
            pkd r5 = pkd.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto La6
            cahd r4 = cahd.S
            goto L1db
        La6:
            pke r5 = pke.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto Lb2
            cahd r4 = cahd.T
            goto L1db
        Lb2:
            pkf r5 = pkf.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto Lbe
            cahd r4 = cahd.P
            goto L1db
        Lbe:
            pkg r5 = pkg.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto Lca
            cahd r4 = cahd.Q
            goto L1db
        Lca:
            pkh r5 = pkh.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto Ld6
            cahd r4 = cahd.w
            goto L1db
        Ld6:
            pki r5 = pki.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto Le2
            cahd r4 = cahd.r
            goto L1db
        Le2:
            pkj r5 = pkj.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto Lee
            cahd r4 = cahd.C
            goto L1db
        Lee:
            pkk r5 = pkk.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto Lfa
            cahd r4 = cahd.O
            goto L1db
        Lfa:
            pkl r5 = pkl.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L106
            cahd r4 = cahd.c
            goto L1db
        L106:
            pkm r5 = pkm.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L112
            cahd r4 = cahd.J
            goto L1db
        L112:
            pkn r5 = pkn.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L11e
            cahd r4 = cahd.O
            goto L1db
        L11e:
            pko r5 = pko.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L12a
            cahd r4 = cahd.L
            goto L1db
        L12a:
            pkp r5 = pkp.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L136
            cahd r4 = cahd.o
            goto L1db
        L136:
            pkq r5 = pkq.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L142
            cahd r4 = cahd.y
            goto L1db
        L142:
            pkr r5 = pkr.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L14e
            cahd r4 = cahd.F
            goto L1db
        L14e:
            pks r5 = pks.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L15a
            cahd r4 = cahd.H
            goto L1db
        L15a:
            pkt r5 = pkt.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L166
            cahd r4 = cahd.G
            goto L1db
        L166:
            pkv r5 = pkv.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L172
            cahd r4 = cahd.I
            goto L1db
        L172:
            pkw r5 = pkw.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L17d
            cahd r4 = cahd.t
            goto L1db
        L17d:
            pkx r5 = pkx.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L188
            cahd r4 = cahd.R
            goto L1db
        L188:
            pkz r5 = pkz.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L193
            cahd r4 = cahd.K
            goto L1db
        L193:
            pla r5 = pla.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L19e
            cahd r4 = cahd.q
            goto L1db
        L19e:
            plb r5 = plb.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L1a9
            cahd r4 = cahd.p
            goto L1db
        L1a9:
            plc r5 = plc.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L1b4
            cahd r4 = cahd.D
            goto L1db
        L1b4:
            pjs r5 = pjs.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L1bf
            cahd r4 = cahd.X
            goto L1db
        L1bf:
            pjt r5 = pjt.a
            boolean r5 = bzlr.h(r4, r5)
            if (r5 == 0) goto L1ca
            cahd r4 = cahd.M
            goto L1db
        L1ca:
            boolean r5 = r4 instanceof pku
            if (r5 != 0) goto L1d9
            boolean r4 = r4 instanceof pky
            if (r4 == 0) goto L1d3
            goto L1d9
        L1d3:
            kotlin.NoWhenBranchMatchedException r6 = new kotlin.NoWhenBranchMatchedException
            r6.<init>()
            throw r6
        L1d9:
            cahd r4 = cahd.a
        L1db:
            r3.copyOnWrite()
            com.google.protobuf.GeneratedMessageLite r5 = r0.instance
            caha r5 = (caha) r5
            int r4 = r4.value
            r5.p = r4
            int r4 = r5.b
            r4 = r4 | 4096(0x1000, float:5.74E-42)
            r5.b = r4
            cahe r4 = cahe.a
            buzi r4 = r4.createBuilder()
            r4.getClass()
            pld r5 = r2.reason
            java.lang.String r5 = r5.b
            bzlq.V(r5, r4)
            pld r2 = r2.reason
            java.lang.String r2 = r2.toString()
            bzlq.O(r2, r4)
            cahe r2 = bzlq.K(r4)
            r3.copyOnWrite()
            com.google.protobuf.GeneratedMessageLite r0 = r0.instance
            caha r0 = (caha) r0
            r0.r = r2
            int r2 = r0.b
            r2 = r2 | 16384(0x4000, float:2.2959E-41)
            r0.b = r2
        L218:
            io.reactivex.subjects.Subject r6 = r6.b
            if (r8 != 0) goto L248
            if (r1 == 0) goto L227
            com.fitbit.fbcomms.pairing.PairingFailureError r7 = (com.fitbit.fbcomms.pairing.PairingFailureError) r7
            pld r7 = r7.reason
            pnb r8 = pmh.a(r7)
            goto L248
        L227:
            boolean r8 = r7 instanceof com.google.protobuf.InvalidProtocolBufferException
            if (r8 == 0) goto L233
            pmn r8 = new pmn
            pkn r7 = pkn.a
            r8.<init>(r7)
            goto L248
        L233:
            boolean r8 = r7 instanceof retrofit2.HttpException
            if (r8 == 0) goto L23f
            pmn r8 = new pmn
            pkw r7 = pkw.a
            r8.<init>(r7)
            goto L248
        L23f:
            boolean r7 = r7 instanceof com.fitbit.bluetooth.fbgatt.rx.GattTransactionException
            if (r7 == 0) goto L246
            pmo r8 = pmo.a
            goto L248
        L246:
            pml r8 = pml.a
        L248:
            r6.onNext(r8)
            return
    }
}
