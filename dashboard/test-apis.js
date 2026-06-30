const admin = require('firebase-admin');
const fs = require('fs');

const serviceAccount = JSON.parse(fs.readFileSync('./tmp/firebase-adminsdk.json', 'utf8'));

if (admin.apps.length === 0) {
  admin.initializeApp({
    credential: admin.credential.cert(serviceAccount)
  });
}
const db = admin.firestore();

async function testDashboard() {
  try {
    const [
      usersSnap, openReportsSnap,
      mangasSnap, weekAgoSnap
    ] = await Promise.all([
      db.collection("publicProfiles").count().get(),
      db.collection("moderationReports").where("status", "==", "open").count().get(),
      db.collection("community_manga").count().get(),
      db.collection("publicProfiles")
        .where("updatedAt", ">=", Date.now() - 7 * 86400000)
        .count().get(),
    ]);
    
    let totalComments = 0;
    let totalReviews = 0;
    const mangaDocs = await db.collection("community_manga").limit(50).get();
    console.log("mangaDocs docs length:", mangaDocs.docs.length);
    const counts = await Promise.all(
      mangaDocs.docs.map(async (m) => {
        const [cSnap, rSnap] = await Promise.all([
          db.collectionGroup
            ? db.collectionGroup("comments").where("mangaId", "==", m.id).count().get()
            : m.ref.collection("reviews").count().get(),
          m.ref.collection("reviews").count().get(),
        ]);
        return { comments: cSnap.data().count, reviews: rSnap.data().count };
      })
    );
    totalReviews = counts.reduce((a, c) => a + c.reviews, 0);
    totalComments = counts.reduce((a, c) => a + c.comments, 0);
    console.log("Dashboard API Success!");
  } catch(e) {
    console.error("Dashboard API Error:", e.message);
  }
}

async function run() {
  await testDashboard();
  process.exit(0);
}
run();
