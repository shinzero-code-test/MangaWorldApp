"use client";

import { useEffect, useState, useCallback } from "react";
import { clientDb } from "@/lib/firebase-client";
import { collection, onSnapshot, query, orderBy, limit as fbLimit, where, CollectionReference, QueryConstraint } from "firebase/firestore";

export function useFirestoreCollection<T = any>(
  path: string,
  constraints?: QueryConstraint[],
  enabled = true
) {
  const [data, setData] = useState<T[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!enabled) { setLoading(false); return; }

    let unsub: (() => void) | undefined;
    try {
      const col = collection(clientDb, path) as CollectionReference;
      const q = constraints && constraints.length > 0 ? query(col, ...constraints) : col;

      unsub = onSnapshot(
        q,
        (snapshot) => {
          const items = snapshot.docs.map((doc) => ({
            id: doc.id,
            ...doc.data(),
          })) as T[];
          setData(items);
          setLoading(false);
          setError(null);
        },
        (err) => {
          console.error(`Firestore error [${path}]:`, err);
          setError(err.message);
          setLoading(false);
        }
      );
    } catch (err: any) {
      setError(err.message);
      setLoading(false);
    }

    return () => { unsub?.(); };
  }, [path, enabled]);

  return { data, loading, error, refetch: () => setLoading(true) };
}

export function useFirestoreDoc<T = any>(path: string, enabled = true) {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!enabled) { setLoading(false); return; }

    const { doc, onSnapshot } = require("firebase/firestore");
    const docRef = doc(clientDb, path);

    const unsub = onSnapshot(
      docRef,
      (snapshot: any) => {
        if (snapshot.exists()) {
          setData({ id: snapshot.id, ...snapshot.data() } as T);
        } else {
          setData(null);
        }
        setLoading(false);
      },
      (err: any) => {
        setError(err.message);
        setLoading(false);
      }
    );

    return () => unsub();
  }, [path, enabled]);

  return { data, loading, error };
}
