package defpackage;

/* JADX INFO: compiled from: PG */
/* JADX INFO: loaded from: G:\Dev\BlueBit\prototype_analysis\tmp-dex\classes6.dex */
final class ahsl extends bzjv implements bzky {
    private /* synthetic */ java.lang.Object L$0;
    java.lang.Object L$1;
    int label;
    final /* synthetic */ defpackage.ahsr this$0;

    public ahsl(defpackage.ahsr r1, bzjc r2) {
            r0 = this;
            r0.this$0 = r1
            r1 = 2
            r0.<init>(r1, r2)
            return
    }

    public final bzjc<bzgp> create(java.lang.Object r2, bzjc<?> r3) {
            r1 = this;
            ahsl r0 = new ahsl
            ahsr r1 = r1.this$0
            r0.<init>(r1, r3)
            r0.L$0 = r2
            return r0
    }

    public final /* bridge */ /* synthetic */ java.lang.Object invoke(java.lang.Object r1, java.lang.Object r2) {
            r0 = this;
            bzri r1 = (bzri) r1
            bzjc r2 = (bzjc) r2
            bzjc r0 = r0.create(r1, r2)
            bzgp r1 = bzgp.a
            ahsl r0 = (defpackage.ahsl) r0
            java.lang.Object r0 = r0.invokeSuspend(r1)
            return r0
    }

    public final java.lang.Object invokeSuspend(java.lang.Object r10) {
            r9 = this;
            bzjk r0 = bzjk.a
            int r1 = r9.label
            r2 = 5
            r3 = 4
            r4 = 3
            r5 = 2
            r6 = 1
            r7 = 0
            if (r1 == 0) goto L64
            if (r1 == r6) goto L53
            if (r1 == r5) goto L40
            if (r1 == r4) goto L2f
            if (r1 == r3) goto L23
            if (r1 != r2) goto L1b
            bwwj.dw(r10)
            goto Lfd
        L1b:
            java.lang.IllegalStateException r9 = new java.lang.IllegalStateException
            java.lang.String r10 = "call to 'resume' before 'invoke' with coroutine"
            r9.<init>(r10)
            throw r9
        L23:
            java.lang.Object r1 = r9.L$0
            ahsd r1 = (defpackage.ahsd) r1
            bwwj.dw(r10)     // Catch: java.lang.Throwable -> L2c
            goto Lb8
        L2c:
            r10 = move-exception
            goto Lbb
        L2f:
            java.lang.Object r1 = r9.L$1
            ahsr r1 = (defpackage.ahsr) r1
            java.lang.Object r4 = r9.L$0
            ahsd r4 = (defpackage.ahsd) r4
            bwwj.dw(r10)     // Catch: java.lang.Throwable -> L3c
            goto La3
        L3c:
            r10 = move-exception
            r1 = r4
            goto Lbb
        L40:
            java.lang.Object r1 = r9.L$1
            ahsr r1 = (defpackage.ahsr) r1
            java.lang.Object r5 = r9.L$0
            ahsd r5 = (defpackage.ahsd) r5
            bwwj.dw(r10)     // Catch: java.lang.Throwable -> L4f
            r8 = r5
            r5 = r1
            r1 = r8
            goto L91
        L4f:
            r10 = move-exception
            r1 = r5
            goto Lbb
        L53:
            java.lang.Object r1 = r9.L$1
            ahsr r1 = (defpackage.ahsr) r1
            java.lang.Object r6 = r9.L$0
            ahsd r6 = (defpackage.ahsd) r6
            bwwj.dw(r10)     // Catch: java.lang.Throwable -> L61
            r10 = r1
            r1 = r6
            goto L82
        L61:
            r10 = move-exception
            r1 = r6
            goto Lbb
        L64:
            bwwj.dw(r10)
            java.lang.Object r10 = r9.L$0
            bzri r10 = (bzri) r10
            ahsr r10 = r9.this$0
            ahsd r1 = new ahsd
            pgp r10 = r10.j
            r1.<init>(r10)
            ahsr r10 = r9.this$0
            r9.L$0 = r1     // Catch: java.lang.Throwable -> L2c
            r9.L$1 = r10     // Catch: java.lang.Throwable -> L2c
            r9.label = r6     // Catch: java.lang.Throwable -> L2c
            java.lang.Object r6 = r10.k(r1, r9)     // Catch: java.lang.Throwable -> L2c
            if (r6 == r0) goto Lfc
        L82:
            r9.L$0 = r1     // Catch: java.lang.Throwable -> L2c
            r9.L$1 = r10     // Catch: java.lang.Throwable -> L2c
            r9.label = r5     // Catch: java.lang.Throwable -> L2c
            java.lang.Object r5 = r10.m(r1, r9)     // Catch: java.lang.Throwable -> L2c
            if (r5 == r0) goto Lfc
            r8 = r5
            r5 = r10
            r10 = r8
        L91:
            ahet r10 = (defpackage.ahet) r10     // Catch: java.lang.Throwable -> L2c
            java.lang.String r10 = r10.a     // Catch: java.lang.Throwable -> L2c
            r9.L$0 = r1     // Catch: java.lang.Throwable -> L2c
            r9.L$1 = r5     // Catch: java.lang.Throwable -> L2c
            r9.label = r4     // Catch: java.lang.Throwable -> L2c
            java.lang.Object r10 = r5.j(r1, r10, r9)     // Catch: java.lang.Throwable -> L2c
            if (r10 == r0) goto Lfc
            r4 = r1
            r1 = r5
        La3:
            ahes r10 = (defpackage.ahes) r10     // Catch: java.lang.Throwable -> L3c
            java.lang.String r10 = r10.a     // Catch: java.lang.Throwable -> L3c
            r1.k = r10     // Catch: java.lang.Throwable -> L3c
            pgp r5 = r1.j     // Catch: java.lang.Throwable -> L3c
            r9.L$0 = r4     // Catch: java.lang.Throwable -> L3c
            r9.L$1 = r7     // Catch: java.lang.Throwable -> L3c
            r9.label = r3     // Catch: java.lang.Throwable -> L3c
            java.lang.Object r10 = r1.o(r10, r5, r9)     // Catch: java.lang.Throwable -> L3c
            if (r10 == r0) goto Lfc
            r1 = r4
        Lb8:
            bzgp r10 = bzgp.a     // Catch: java.lang.Throwable -> L2c
            goto Lbf
        Lbb:
            java.lang.Object r10 = bwwj.dv(r10)
        Lbf:
            ahsr r3 = r9.this$0
            boolean r4 = bzgf.e(r10)
            if (r4 == 0) goto Le0
            r4 = r10
            bzgp r4 = (bzgp) r4
            r3.d()
            ahsd r4 = r3.l
            java.lang.Object r5 = r4.a
            onc r5 = (onc) r5
            r5.f()
            r4.i()
            io.reactivex.subjects.Subject r3 = r3.b
            pmt r4 = pmt.a
            r3.onNext(r4)
        Le0:
            ahsr r3 = r9.this$0
            java.lang.Throwable r4 = bzgf.d(r10)
            if (r4 == 0) goto Lfd
            r3.p(r4, r7)
            ahsd r4 = r3.l
            r4.i()
            r9.L$0 = r10
            r9.L$1 = r7
            r9.label = r2
            java.lang.Object r9 = r3.n(r1, r9)
            if (r9 != r0) goto Lfd
        Lfc:
            return r0
        Lfd:
            bzgp r9 = bzgp.a
            return r9
    }
}
