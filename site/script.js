  // Below is for the graph of the magnetic field strength (it updates live)
const ctx = document.getElementById('graph').getContext('2d');
Chart.defaults.font.family = 'Geist_Mono, monospace';
const graph = new Chart (ctx, {
    type: 'line',
    data: {
    labels: [],
    datasets: [{
        label: 'Magnetic Field (mT)',
        data: [],
        borderColor: '#85CB33',
        backgroundColor: 'rgba(133, 203, 51, 0.1)',
        borderWidth: 2,
        pointRadius: 0,
        tension: 0.3
    }]
},
    
    options: {
        devicePixelRatio: window.devicePixelRatio,
        animation: false,
        responsive:true,
        maintainAspectRatio: false,
        plugins: {
            title: {
                display: true,
                text: 'Magnetic Field Vs. Time',
                color: '#EBEBEB',
                font: { size: 36 },
                padding: { bottom: 20 } 
            },
            legend: {
                labels: { color: '#EBEBEB', font: { size: 24 } },
            }
        },
        scales: {
        x: { 
            title: { display: true, text: 'Time (ms)', color: '#EBEBEB', font: {size:24}, padding: { top: 20 }}, 
            grid:{display:false},                  
        },
        y: { 
            title: { display: true, text: 'Magnetic Field Strength (mT)', color: '#EBEBEB', font: {size:24}, padding: { bottom: 20 } },
            ticks: { color: '#EBEBEB', font: { size: 18 }, stepSize:50},
            grid: { color: 'rgba(235, 235, 235, 0.40)' }, // #EBEBEB but at 60% opacity
                min: -50,
                max: 100,
                beginAtZero: false 
        }
    }
        
    }
});

const displayPoints = 50; // Max number of points to display on the graph
let time = 0; 

let socket = null;

const magField = document.getElementById("mag"); // Magnetic field strength (mT)
const rpm = document.getElementById("rpm"); // RPM (determined from peaks in the magField)
// const kV = document.getElementById("kv"); // kV rating (RPM / Voltage)

let isRunning = false;
let isPaused  = false;

const allLabels = [];
const allData   = [];
let viewOffset = null;

const scrollRange = document.getElementById('scroll-range');


function handleStart() {
    if (!isRunning) {
        
        isRunning = true;
        isPaused  = false;
        document.getElementById('btn-start').textContent = 'PAUSE';
        connectSocket();
    } else if (!isPaused) {
        // If currently running then pause
        isPaused = true;
        document.getElementById('btn-start').textContent = 'RESUME';
    } else {
        // Is currently paused then resume
        isPaused = false;
        document.getElementById('btn-start').textContent = 'PAUSE';
    }
}

function handleClear() {
    isRunning = false;
    isPaused  = false;

    if (socket) {
        socket.onclose = null;
        socket.close();
        socket = null;
    }

    graph.data.labels = [];
    graph.data.datasets[0].data = [];
    graph.update();  // Clear the graph


    // resetting values
    time = 0;
    magField.textContent = '000 mT';
    rpm.textContent      = '0000';

    document.getElementById('btn-start').textContent = 'START';

    allLabels.length = 0;
    allData.length   = 0;
    viewOffset       = null;
    updateScrollControls();
}


function connectSocket() {
    socket = new WebSocket("ws://localhost:8081"); 
    socket.onopen = () => {
        magField.textContent = "Connected...";
        console.log("WebSocket connection established. for browser");
        // kV.textContent = "Connected...";
    };

    socket.onmessage = (event) => {

        if (isPaused) return; // While paused, incoming messages are ignored but still proccessed

        let mag;
        let data = {}; // For the graph (only needs magnetic field strength)
        
        try {
            // Parses the JSON msg for each value (magnetic field, rpm, kV) kV didn't end up being super reasonable too add in
            data = JSON.parse(event.data);
            mag = data.magneticField;
        } catch { 
            const match = event.data.match(/([\d.]+)/);
            mag = match ? parseFloat(match[1]) : null;
        }


        if (data.magneticField !== undefined)
            magField.textContent = `${data.magneticField.toFixed(2)} mT`;
        if (data.rpm !== undefined)                   
            rpm.textContent = Math.round(data.rpm);
        if (data.kV !== undefined) 
            kV.textContent = `${Math.round(data.kV)} kV`;
        

        if(mag !== null && mag !== undefined) {
            allLabels.push(time++);
            allData.push(mag);

            

            updateScrollControls();
            refreshChart();
        }
    };

    socket.onclose = () => {
        magField.textContent = "Disconnected.";
    };
    // Error 
    socket.onerror = (error) => {
        magField.textContent = "Error";
    };
}

function refreshChart() {
    const total = allData.length;
    let start;

    if (viewOffset === null) {
        start = Math.max(0, total - displayPoints);
    } else {
        start = Math.min(viewOffset, Math.max(0, total - displayPoints));
    }

    const end = Math.min(start + displayPoints, total);
    graph.data.labels            = allLabels.slice(start, end);
    graph.data.datasets[0].data  = allData.slice(start, end);
    graph.update();

    if (viewOffset !== null) {
        scrollRange.value = start;
    } else {
        scrollRange.value = scrollRange.max;
    }
}

function updateScrollControls() {
    const total      = allData.length;
    const max        = Math.max(0, total - displayPoints);
    scrollRange.max  = max;

    const hasHistory = total > displayPoints;
    scrollRange.disabled                                  = !hasHistory;
    document.getElementById('btn-scroll-end').disabled   = !hasHistory;
}



function scrollToEnd() {
    viewOffset = null;
    refreshChart();
}

function onScrollRange(val) {
    const total = allData.length;
    const max   = Math.max(0, total - displayPoints);
    const v     = parseInt(val);
    viewOffset  = (v >= max) ? null : v;
    refreshChart();
}
