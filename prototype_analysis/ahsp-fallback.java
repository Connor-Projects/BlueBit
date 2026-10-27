package defpackage;

/* JADX INFO: compiled from: PG */
/* JADX INFO: loaded from: G:\Dev\BlueBit\prototype_analysis\tmp-dex\classes6.dex */
final class ahsp extends bzjv implements bzky {
    final /* synthetic */ android.content.Context $context;
    int label;
    final /* synthetic */ defpackage.ahsr this$0;

    public ahsp(defpackage.ahsr r1, android.content.Context r2, bzjc r3) {
            r0 = this;
            r0.this$0 = r1
            r0.$context = r2
            r1 = 2
            r0.<init>(r1, r3)
            return
    }

    public final bzjc<bzgp> create(java.lang.Object r2, bzjc<?> r3) {
            r1 = this;
            ahsp r2 = new ahsp
            ahsr r0 = r1.this$0
            android.content.Context r1 = r1.$context
            r2.<init>(r0, r1, r3)
            return r2
    }

    public final /* bridge */ /* synthetic */ java.lang.Object invoke(java.lang.Object r1, java.lang.Object r2) {
            r0 = this;
            bzri r1 = (bzri) r1
            bzjc r2 = (bzjc) r2
            bzjc r0 = r0.create(r1, r2)
            bzgp r1 = bzgp.a
            ahsp r0 = (defpackage.ahsp) r0
            java.lang.Object r0 = r0.invokeSuspend(r1)
            return r0
    }

    public final java.lang.Object invokeSuspend(java.lang.Object r13) {
            r12 = this;
            bzjk r0 = bzjk.a
            int r1 = r12.label
            r2 = 2
            r3 = 1
            if (r1 == 0) goto L20
            if (r1 == r3) goto L1c
            if (r1 != r2) goto L14
            bwwj.dw(r13)     // Catch: java.lang.Throwable -> L11
            goto Lab
        L11:
            r13 = move-exception
            goto Laf
        L14:
            java.lang.IllegalStateException r12 = new java.lang.IllegalStateException
            java.lang.String r13 = "call to 'resume' before 'invoke' with coroutine"
            r12.<init>(r13)
            throw r12
        L1c:
            bwwj.dw(r13)
            goto L86
        L20:
            bwwj.dw(r13)
            ahsr r13 = r12.this$0
            java.util.concurrent.atomic.AtomicInteger r13 = r13.h
            int r13 = r13.get()
            if (r13 <= 0) goto L5e
            ahsr r13 = r12.this$0
            java.util.concurrent.atomic.AtomicInteger r13 = r13.h
            int r13 = r13.get()
            ahsr r1 = r12.this$0
            int r4 = r1.f
            if (r13 >= r4) goto L5e
            j$.time.Duration r13 = r1.g
            long r4 = r13.getSeconds()
            bzpy r1 = bzpy.d
            long r4 = bzje.H(r4, r1)
            int r13 = r13.getNano()
            bzpy r1 = bzpy.a
            long r6 = bzje.G(r13, r1)
            long r4 = bzpw.h(r4, r6)
            r12.label = r3
            java.lang.Object r13 = bzrk.g(r4, r12)
            if (r13 != r0) goto L86
            goto Lae
        L5e:
            ahsr r13 = r12.this$0
            java.util.concurrent.atomic.AtomicInteger r13 = r13.h
            int r13 = r13.get()
            ahsr r1 = r12.this$0
            int r4 = r1.f
            if (r13 < r4) goto L86
            abnb r13 = r1.n
            com.google.android.apps.fitbit.app.device.onboarding.impl.tracker.pairingflow.anari.ScanningFailureError$MaxRetriesExceeded r0 = com.google.android.apps.fitbit.app.device.onboarding.impl.tracker.pairingflow.anari.ScanningFailureError.MaxRetriesExceeded.a
            r13.f(r0)
            ahsr r13 = r12.this$0
            abnb r13 = r13.n
            r13.e()
            ahsr r12 = r12.this$0
            io.reactivex.subjects.Subject r12 = r12.b
            pmv r13 = pmv.a
            r12.onNext(r13)
            bzgp r12 = bzgp.a
            return r12
        L86:
            ahsr r13 = r12.this$0
            java.util.concurrent.atomic.AtomicInteger r13 = r13.h
            r13.incrementAndGet()
            ahsr r13 = r12.this$0
            nrq r1 = r13.i
            if (r1 == 0) goto L99
            r1.g()
            r1 = 0
            r13.i = r1
        L99:
            ahsr r13 = r12.this$0
            android.content.Context r1 = r12.$context
            qge r13 = r13.c
            bxsz r13 = r13.a(r1)
            r12.label = r2     // Catch: java.lang.Throwable -> L11
            java.lang.Object r13 = bxmx.p(r13, r12)     // Catch: java.lang.Throwable -> L11
            if (r13 == r0) goto Lae
        Lab:
            qgd r13 = (qgd) r13     // Catch: java.lang.Throwable -> L11
            goto Lb3
        Lae:
            return r0
        Laf:
            java.lang.Object r13 = bwwj.dv(r13)
        Lb3:
            ahsr r0 = r12.this$0
            boolean r1 = bzgf.e(r13)
            if (r1 == 0) goto L153
            r1 = r13
            qgd r1 = (qgd) r1
            pnb r4 = r1.b
            boolean r5 = r1 instanceof qga
            if (r5 == 0) goto L134
            qga r1 = (qga) r1
            nrq r1 = r1.a
            r0.i = r1
            ahss r1 = new ahss
            nrq r5 = r0.i
            r6 = 127(0x7f, float:1.78E-43)
            if (r5 == 0) goto Le0
            nqt r5 = r5.d()
            if (r5 == 0) goto Le0
            java.lang.Integer r5 = r5.c
            if (r5 == 0) goto Le0
            int r6 = r5.intValue()
        Le0:
            nrq r5 = r0.i
            if (r5 == 0) goto Lee
            nqt r5 = r5.d()
            if (r5 == 0) goto Lee
            int r5 = r5.e
            if (r5 != 0) goto Lef
        Lee:
            r5 = r3
        Lef:
            r1.<init>(r6, r5)
            abnb r5 = r0.n
            java.lang.Object r5 = r5.a
            int r6 = r1.a
            onc r5 = (onc) r5
            java.lang.Object r7 = r5.b
            r8 = r7
            buzi r8 = (buzi) r8
            r8.copyOnWrite()
            cagz r7 = (cagz) r7
            com.google.protobuf.GeneratedMessageLite r9 = r7.instance
            caha r9 = (caha) r9
            caha r10 = caha.a
            int r10 = r9.c
            r11 = 2097152(0x200000, float:2.938736E-39)
            r10 = r10 | r11
            r9.c = r10
            r9.ab = r6
            int r1 = r1.b
            int r1 = r1 + (-1)
            if (r1 == 0) goto L11d
            if (r1 == r3) goto L11e
            r2 = 4
            goto L11e
        L11d:
            r2 = r3
        L11e:
            r8.copyOnWrite()
            com.google.protobuf.GeneratedMessageLite r1 = r7.instance
            caha r1 = (caha) r1
            int r2 = r2 + (-1)
            r1.ae = r2
            int r2 = r1.c
            r3 = 16777216(0x1000000, float:2.3509887E-38)
            r2 = r2 | r3
            r1.c = r2
            r5.f()
            goto L14e
        L134:
            boolean r2 = r1 instanceof qgc
            if (r2 == 0) goto L140
            abnb r1 = r0.n
            com.google.android.apps.fitbit.app.device.onboarding.impl.tracker.pairingflow.anari.ScanningFailureError$MultipleDevicesFound r2 = com.google.android.apps.fitbit.app.device.onboarding.impl.tracker.pairingflow.anari.ScanningFailureError.MultipleDevicesFound.a
            r1.f(r2)
            goto L14e
        L140:
            boolean r1 = r1 instanceof qgb
            if (r1 == 0) goto L14e
            abnb r1 = r0.n
            com.google.android.apps.fitbit.app.device.onboarding.impl.tracker.pairingflow.anari.ScanningFailureError$DeviceNotFound r2 = new com.google.android.apps.fitbit.app.device.onboarding.impl.tracker.pairingflow.anari.ScanningFailureError$DeviceNotFound
            r2.<init>()
            r1.f(r2)
        L14e:
            io.reactivex.subjects.Subject r0 = r0.b
            r0.onNext(r4)
        L153:
            ahsr r0 = r12.this$0
            java.lang.Throwable r13 = bzgf.d(r13)
            if (r13 == 0) goto L16c
            abnb r13 = r0.n
            com.google.android.apps.fitbit.app.device.onboarding.impl.tracker.pairingflow.anari.ScanningFailureError$UnknownError r1 = new com.google.android.apps.fitbit.app.device.onboarding.impl.tracker.pairingflow.anari.ScanningFailureError$UnknownError
            r1.<init>()
            r13.f(r1)
            io.reactivex.subjects.Subject r13 = r0.b
            pmw r0 = pmw.a
            r13.onNext(r0)
        L16c:
            ahsr r12 = r12.this$0
            abnb r12 = r12.n
            r12.e()
            bzgp r12 = bzgp.a
            return r12
    }
}
