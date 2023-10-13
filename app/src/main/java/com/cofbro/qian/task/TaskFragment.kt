package com.cofbro.qian.task

import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.alibaba.fastjson.JSONObject
import com.cofbro.hymvvmutils.base.BaseFragment
import com.cofbro.qian.data.URL
import com.cofbro.qian.databinding.FragmentTaskBinding
import com.cofbro.qian.mapsetting.MapActivity
import com.cofbro.qian.scan.ScanActivity
import com.cofbro.qian.utils.CacheUtils
import com.cofbro.qian.utils.Constants
import com.cofbro.qian.utils.getStringExt
import com.cofbro.qian.wrapper.WrapperActivity
import com.hjq.toast.ToastUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.UnsupportedEncodingException

import java.net.URLEncoder

import java.util.regex.Matcher
import java.util.regex.Pattern


class TaskFragment : BaseFragment<TaskViewModel, FragmentTaskBinding>() {
    private var activeId = ""
    private var latitude = ""
    private var longitude = ""
    private var signTypeData: JSONObject? = null
    private var preSignUrl = ""
    private var refreshing = false
    private val requestCode = 1
    private var activity: WrapperActivity? = null
    private var taskAdapter: TaskAdapter? = null
    override fun onAllViewCreated(savedInstanceState: Bundle?) {
        initArgs()
        initObserver()
        initView()
        doNetwork()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == AppCompatActivity.RESULT_OK) {
            val result = data?.getStringExtra("result")
            // SIGNIN:aid=402742574&source=15&Code=402742574&enc=548DF0246153AF088E756B59F33BF3F4
            // https://mobilelearn.chaoxing.com/widget/sign/e?id=2000072435046&c=2000072435046&enc=BC9662672047A2F2E4A607CC59762973&DB_STRATEGY=PRIMARY_KEY&STRATEGY_PARA=id
            // 这里的id包含url中的所有参数
            val id = result?.substringAfter("id=")
            if (result != null) {
                Log.v("LOG_RESULT:",result)
            }
            signWithCamera(id)
        }
    }

    private fun initArgs() {
        activity = requireActivity() as WrapperActivity
    }

    private fun initView() {
        // rv
        binding?.rvSignTask?.apply {
            taskAdapter = TaskAdapter()
            taskAdapter?.setItemClickListener { itemData ->
                sign(itemData)
            }
            adapter = taskAdapter
            layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
        }

        // refresh
        binding?.rflSignTask?.apply {
            setOnRefreshListener {
                autoRefresh()
                refreshing = true
                doNetwork()
            }
        }
    }

    private fun initObserver() {
        // 活动列表
        viewModel.queryActiveTaskListLiveData.observe(this) {
            lifecycleScope.launch(Dispatchers.IO) {
                if (refreshing) {
                    refreshing = false
                    binding?.rflSignTask?.finishRefresh()
                }
                val data = it.data?.body?.string()
                Log.v("result:Lists", it.toString())
                withContext(Dispatchers.Main) {
                    JSONObject.parseObject(data)?.let {
                        taskAdapter?.setData(it)
                        Log.v("result:List",it.toString())
                    }
                }
            }
        }

        // 获取签到类型
        viewModel.signTypeLiveData.observe(this) {
            lifecycleScope.launch(Dispatchers.IO) {
                signTypeData = JSONObject.parseObject(it.data?.body?.string())
                // 签到类型获取后，开始签到
                realSign(signTypeData)
            }

        }

        viewModel.signCodeLiveData.observe(this) {
            lifecycleScope.launch(Dispatchers.IO) {
                val data = it.data?.body?.string()
            }
        }

        viewModel.preSignLiveData.observe(this) {
            lifecycleScope.launch(Dispatchers.IO) {
                val data = it.data?.body?.string()
                data?.let {
                    val html = Jsoup.parse(it)
                    latitude = html.getElementById("locationLatitude").`val`()
                    longitude = html.getElementById("locationLongitude").`val`()
                    if (latitude.isNotEmpty() && longitude.isNotEmpty()) {
                        val uid = CacheUtils.cache["uid"] ?: ""
                        toMapActivity( activeId, uid = uid, latitude, longitude)
                    }
                }

            }
        }

        // 签到
        viewModel.signLiveData.observe(this) {
            lifecycleScope.launch(Dispatchers.IO) {
                val data = it.data?.body?.string()
                withContext(Dispatchers.Main) {
                    data?.let {
                        if (data.contains("签到成功") || data.contains("success")) {
                            ToastUtils.show("签到成功!")
                        } else if (data.contains("签到过了")) {
                            ToastUtils.show("您已经签到过了")
                        }
                    }
                }
            }
        }
    }

    private fun doNetwork() {
        activity?.let {
            queryAllActiveTask(it.courseId, it.classId, it.cpi)
        }
    }

    private fun queryAllActiveTask(courseId: String, classId: String, cpi: String) {
        // 查询所有活动
        val uid = CacheUtils.cache["uid"] ?: ""
        viewModel.queryActiveTaskList(URL.gatActiveTaskListPath(courseId, classId, uid, cpi))
        Log.v("sign_task" , URL.gatActiveTaskListPath(courseId, classId, uid, cpi))
    }

    private fun sign(itemData: JSONObject) {
        // aid
        val id = itemData.getStringExt(Constants.TaskList.ID)
        activeId = id
        // 2代表签到活动
        val type = itemData.getStringExt(Constants.TaskList.ACTIVE_TYPE)
        // 预签到地址
        preSignUrl = itemData.getStringExt(Constants.TaskList.PRE_SIGN_URL)
        // 1 -> 未签，2 -> 已签
        val status = itemData.getStringExt(Constants.TaskList.STATUS)
        if (status == Constants.STATUS.CLOSE) {
            ToastUtils.show("签到已过期，下次早点来~")
            return
        }
        if (type == Constants.ACTIVITY.SIGN) {
            lifecycleScope.launch(Dispatchers.IO) {
                // 查询签到类型
                viewModel.findSignType(URL.getSignType(id))
            }
        }

    }

    private suspend fun realSign(itemData: JSONObject?) {
        val type = itemData?.getStringExt(Constants.SIGN.OTHER_ID)
        val ifPhoto = itemData?.getStringExt(Constants.SIGN.IF_PHOTO)
        val id = itemData?.getStringExt(Constants.SIGN.ID) ?: ""
        when (type) {
            // 二维码签到
            Constants.SIGN.SCAN_QR -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    // 预签到
                    viewModel.preSign(preSignUrl)
                    // 跳转扫码界面
                    toScanActivity()
                }
            }
            // 普通签到
            Constants.SIGN.NORMAl -> {
                if (ifPhoto == Constants.SIGN.PHOTO) {
                    // TODO: 照片签到
                } else {
                    viewModel.preSign(preSignUrl)
                    // 签到
                    signNormally(id)
                }
            }
            // 手势签到，签到码签到
            Constants.SIGN.GESTURE, Constants.SIGN.SIGN_CODE -> {
                viewModel.preSign(preSignUrl)
                signNormally(id)
            }
            // 定位签到
            Constants.SIGN.LOCATION -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    viewModel.preSign(preSignUrl)
                    Log.v("preSignUrl", preSignUrl)
                    val uid = CacheUtils.cache["uid"] ?: ""
                    Log.v("preSignUrl:",id)
//                    val default = "https://mobilelearn.chaoxing.com/pptSign/stuSignajax?name=%E9%98%BF%E9%87%8C%E4%B8%AD%E5%BF%83%C2%B7%E6%9C%9B%E4%BA%ACB%E5%BA%A7&address=%E6%9C%9B%E4%BA%AC%E4%B8%9C%E5%9B%AD4%E5%8C%BA4%E5%8F%B7%E6%A5%BC&activeId=1000073717972&uid=191970813&clientip=&latitude=40.002528&longitude=116.489878&fid=1840&appType=15&ifTiJiao=1"
//                    signLoction(default)
                }


            }
        }
    }
    private fun toMapActivity(aid: String,uid: String, lat: String, lon: String){
        /**
         * 需要传递
         *         name:String
         *         address:String,
         *         aid: String, 。。。。0
         *         uid:String, 。。。。 1
         *         lat:Double,
         *         long:Double,
         */
        val intent = Intent(requireActivity(), MapActivity::class.java)
        val list:ArrayList<String> = ArrayList()
        list.add(aid)
        list.add(uid)
        intent.putExtra("EXTRA_MSG", list)
        intent.putExtra("lat", lat)
        intent.putExtra("lon", lon)
        startActivity(intent)
    }
    private fun toScanActivity() {
        val intent = Intent(requireActivity(), ScanActivity::class.java)
        startActivityForResult(intent, requestCode)
    }

    private fun signWithCamera(id: String?) {
        if (id.isNullOrEmpty()) return
        //val uid = CacheUtils.cache["uid"] ?: ""
        // 暂时不用在url中拼接uid
        lifecycleScope.launch(Dispatchers.IO) {
            viewModel.sign(URL.getSignWithCameraPath(id))
        }
    }

    private suspend fun signNormally(aid: String) {
        // https://mobilelearn.chaoxing.com/widget/sign/pcStuSignController/preSign?activeId=2000072607796
        activity?.let {
            viewModel.sign(URL.getNormalSignPath(it.courseId, it.classId, aid))
        }
    }
    private suspend fun signLoction(api:String) {
        activity?.let {
            viewModel.sign(api)
        }
    }

    /**
     * 服务端现已不下发签到码，客户端发起请求后由服务端校验，
     * 因此暂时没有方法能够拿到密码
     */
    private suspend fun signWithSignCode(aid: String) {
        viewModel.getSignCode(URL.getSignCodePath(aid))
    }

}