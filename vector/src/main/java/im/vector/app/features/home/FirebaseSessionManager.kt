package im.vector.app.features.home



//object FirebaseSessionManager {
//    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
//    fun isUserSignedIn(): Boolean = auth.currentUser != null
//    fun getCurrentUser(): FirebaseUser? = auth.currentUser
//    fun signOut() = auth.signOut()
//    fun refreshToken(onComplete: (String?) -> Unit) {
//        auth.currentUser?.getIdToken(true)?.addOnCompleteListener { task ->
//            onComplete(if (task.isSuccessful) task.result?.token else null)
//        }
//    }
//}
