// Channel list. Each channel has one or more sources ("lines", 线路), tried in order.
// If a line lags or dies, the player moves to the next one automatically.
//
// Source types:
//   hls(url)       - direct HLS stream (.m3u8) from the broadcaster's own server
//   ytLive(id)     - whatever is live right now on an official YouTube channel (UC... id)
//   ytVideo(id)    - a specific YouTube live video id (changes when the broadcaster restarts it)
//   cctv(slug)     - opens CCTV's own live page on tv.cctv.com (CCTV won't play inside
//                    another site). The TV app enlarges its video to full screen.
//
// Every source here is a free stream published by the broadcaster itself.
// Run tools/check_streams.py to see which hls() ones still work.

const hls = (url) => ({ type: 'hls', url });
const ytLive = (channel) => ({ type: 'yt', channel });
const ytVideo = (video) => ({ type: 'yt', video });
const cctv = (slug) => ({ type: 'web', url: `https://tv.cctv.com/live/${slug}/` });
const tvbNews = () => ({ type: 'web', url: 'https://news.tvb.com/en/live/C' });  // TVB's own site, same idea as cctv()

window.CHANNEL_GROUPS = [
  {
    name: '央视 CCTV',
    channels: [
      { id: 'cctv4', name: 'CCTV-4', sub: '中文国际', sources: [cctv('cctv4')] },
      { id: 'cctv1', name: 'CCTV-1', sub: '综合', sources: [cctv('cctv1')] },
      { id: 'cctv13', name: 'CCTV-13', sub: '新闻', sources: [cctv('cctv13')] },
      { id: 'cctv6', name: 'CCTV-6', sub: '电影', sources: [cctv('cctv6')] },
      { id: 'cctv8', name: 'CCTV-8', sub: '电视剧', sources: [cctv('cctv8')] },
      { id: 'cctv3', name: 'CCTV-3', sub: '综艺', sources: [cctv('cctv3')] },
      { id: 'cctv9', name: 'CCTV-9', sub: '纪录', sources: [cctv('cctvjilu')] },
      { id: 'cctv11', name: 'CCTV-11', sub: '戏曲', sources: [cctv('cctv11')] },
      { id: 'cctv5', name: 'CCTV-5', sub: '体育', sources: [cctv('cctv5')] },
      { id: 'cctv2', name: 'CCTV-2', sub: '财经', sources: [cctv('cctv2')] },
    ],
  },
  {
    name: '新闻',
    channels: [
      { id: 'tvbnews', name: '無綫新聞台', sub: 'TVB News 83台 · 粵語', sources: [tvbNews()] },
      { id: 'phoenix', name: '凤凰卫视', sub: 'Phoenix TV 24小时',
        sources: [ytLive('UC4vnLYInDvXtLKGOZieMeMw'), ytVideo('Ry--eMIjYLQ')] },
      { id: 'cgtn', name: 'CGTN', sub: '中国国际电视台 (英语)',
        sources: [hls('https://amg00405-rakutentv-cgtn-rakuten-i9tar.amagi.tv/master.m3u8'),
                  ytLive('UCgrNz-aDmcr2uuto8_DL2jg'), ytVideo('0i7n3r01L2U')] },
      { id: 'cgtnbiz', name: 'CGTN 财经', sub: 'CGTN Global Biz',
        sources: [hls('https://fastlive.cctvplus.com/out/v1/2b3d8a0c805d437f9dc85b764c2ac599/index.m3u8')] },
    ],
  },
  {
    name: '电视剧 · 电影',
    channels: [
      { id: 'cctvdrama', name: '央视剧场 24小时', sub: '三国 · 水浒 · 西游 · 康熙 · 雍正',
        sources: [ytVideo('t4IqvJ9eMI0'), ytVideo('qguQrbBL1vo'), ytVideo('UgOi92IvONg'),
                  ytVideo('clQL5keqAEQ'), ytVideo('VaTLqXjEz0k'), ytLive('UC7Vl0YiY0rDlovqcCFN4yTA')] },
      { id: 'cctvmovie', name: '电影大剧院', sub: '24小时电影连播',
        sources: [ytLive('UCnXAVNOIqCMqgN9yHwSfXdQ'), ytVideo('0lEnHGs_aZc')] },
      { id: 'qtv3', name: '青岛影视', sub: 'QTV-3',
        sources: [hls('https://video10.qtv.com.cn/drm/qtv3at/manifest.m3u8')] },
      { id: 'hrbmovie', name: '哈尔滨影视', sub: 'Harbin Movies',
        sources: [hls('https://stream.hrbtv.net/yspd/playlist.m3u8')] },
    ],
  },
  {
    name: '卫视 · 地方台',
    channels: [
      { id: 'hunan', name: '湖南卫视', sub: 'Hunan TV',
        sources: [hls('https://hlsal-ldvt.qing.mgtv.com/nn_live/nn_x64/dWlwPTEyNy4wLjAuMSZ1aWQ9cWluZy1jbXMmbm5fdGltZXpvbmU9OCZjZG5leF9pZD1hbF9obHNfbGR2dCZ1dWlkPTliODY4NmU5ZTM2YzYwMmMmZT02OTE0NjA0JnY9MSZpZD1ITldTWkdTVCZzPTcwN2RiYTc2YzJjNmJmMTQ4MmUyZGYzOWU2NWM3YWFi/HNWSZGST.m3u8')] },
      { id: 'hebei', name: '河北卫视', sub: 'Hebei TV',
        sources: [hls('https://event.pull.hebtv.com/live/live101.m3u8')] },
      { id: 'zjintl', name: '浙江国际', sub: 'Zhejiang International',
        sources: [hls('https://ali-m-l.cztv.com/channels/lantian/channel10/1080p.m3u8')] },
      { id: 'gztv', name: '广州综合', sub: 'Guangzhou TV',
        sources: [hls('https://tencentplaybusiness.gztv.com/live/zonghes.m3u8')] },
      { id: 'dongguan', name: '东莞新闻', sub: 'Dongguan News',
        sources: [hls('https://stream.sun0769.com/dgrtv1/mp4tv1_800/index.m3u8')] },
      { id: 'qtv1', name: '青岛新闻', sub: 'QTV-1',
        sources: [hls('https://video10.qtv.com.cn/drm/qtv1at/manifest.m3u8')] },
      { id: 'hrbnews', name: '哈尔滨新闻', sub: 'Harbin News',
        sources: [hls('https://stream.hrbtv.net/xwzh/playlist.m3u8?_upt=ef41dd531755913594')] },
    ],
  },
  {
    name: '纪录 · 旅游',
    channels: [
      { id: 'cgtndoc', name: 'CGTN 纪录', sub: 'CGTN Documentary',
        sources: [hls('https://amg00405-rakutentv-cgtndocumentary-rakuten-0ql8j.amagi.tv/master.m3u8')] },
      { id: 'discover', name: '发现中国', sub: 'Discovering China',
        sources: [hls('https://fastlive.cctvplus.com/out/v1/8442c3bfd4c64a3984389328f6087879/index.m3u8')] },
      { id: 'travel', name: '中国旅游', sub: 'China Travel',
        sources: [hls('https://fastlive.cctvplus.com/out/v1/ca6f9297b7314a63959435028af287fc/index.m3u8')] },
    ],
  },
  {
    name: '台湾新闻',
    channels: [
      { id: 'tvbs', name: 'TVBS 新闻', sub: 'TVBS News',
        sources: [ytLive('UC5nwNW4KdC0SzrhF9BXEYOQ'), ytVideo('m_dhMSvUCIc')] },
      { id: 'ebc', name: '东森新闻', sub: 'EBC News',
        sources: [ytLive('UCR3asjvr_WAaxwJYEDV_Bfw'), ytVideo('V1p33hqPrUk')] },
      { id: 'cti', name: '中天新闻', sub: 'CTi News',
        sources: [ytLive('UC5l1Yto5oOIgRXlI4p4VKbw'), ytVideo('vr3XyVCR4T0')] },
      { id: 'ftv', name: '民视新闻', sub: 'FTV News',
        sources: [ytLive('UC2VmWn8dAqkzlQqvy02E1PA'), ytVideo('ylYJSBUgaMA')] },
      { id: 'pts', name: '公视新闻', sub: 'PTS News',
        sources: [ytLive('UCexpzYDEnfmAvPSfG4xbcjA'), ytVideo('hg4nYb3jE9A')] },
      { id: 'ctv', name: '中视新闻', sub: 'CTV News',
        sources: [ytLive('UCmH4q-YjeazayYCVHHkGAMA'), ytVideo('TCnaIE_SAtM')] },
      { id: 'gnews', name: '寰宇新闻', sub: 'Global News TV',
        sources: [ytLive('UCp2f7tGJGN6R9Muxipem8Nw'), ytVideo('6IquAgfvYmc')] },
    ],
  },
];
